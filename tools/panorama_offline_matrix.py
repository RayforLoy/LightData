"""Local-only OpenCV full-pipeline comparison. Inputs are private ZIPs.

Requires numpy and opencv-python. No network, no extraction, no GUI.
Results are experiments, never an automatic visual-quality pass.
"""
import argparse
import json
import math
from pathlib import Path
import time
import zipfile

import cv2 as cv
import numpy as np


def read_frames(path, edge, keyframes):
    images, metadata, names = [], [], []
    with zipfile.ZipFile(path) as z:
        files = [n for n in z.namelist() if n.lower().endswith(('.jpg', '.jpeg'))]
        files.sort(key=lambda n: (0, int(Path(n).stem)) if Path(n).stem.isdigit() else (1, n))
        selected = np.unique(np.linspace(0, len(files)-1, min(keyframes, len(files))).round().astype(int))
        for i in selected:
            name = files[i]
            im = cv.imdecode(np.frombuffer(z.read(name), np.uint8), cv.IMREAD_COLOR)
            if im is None:
                raise ValueError(name)
            h, w = im.shape[:2]
            scale = min(1., edge/max(h, w))
            images.append(cv.resize(im, (round(w*scale), round(h*scale)), interpolation=cv.INTER_AREA))
            meta = name.rsplit('.', 1)[0]+'.json'
            metadata.append(json.loads(z.read(meta)) if meta in z.namelist() else None)
            names.append(name)
    if len(images) < 2:
        raise ValueError('Need at least two images')
    return images, metadata, names, len(files)


def fit(images, metadata, feature, adjustment, confidence, use_gyro, focal35=None, diagnostics=None):
    finder = {'sift': lambda: cv.SIFT_create(6000), 'orb': lambda: cv.ORB_create(6000),
              'akaze': cv.AKAZE_create}[feature]()
    features = [cv.detail.computeImageFeatures2(finder, im) for im in images]
    matcher = cv.detail_BestOf2NearestMatcher(False, .3)
    matches = matcher.apply2(features)
    if use_gyro:
        indices = list(range(len(images)))
    else:
        indices = list(map(int, cv.detail.leaveBiggestComponent(features, matches, confidence)))
        if len(indices) < 2:
            raise ValueError('No connected component of two images')
        # Python lists are not reduced in-place by leaveBiggestComponent.
        if len(indices) != len(images):
            features = [features[i] for i in indices]
            matches = matcher.apply2(features)
    matcher.collectGarbage()
    cameras = []
    if use_gyro:
        origin = np.array(metadata[0]['axes']).reshape(3, 3)
        flip = np.diag([1., -1., -1.])
        for im, meta in zip(images, metadata):
            h, w = im.shape[:2]
            c = cv.detail_CameraParams()
            c.focal = w/(2*meta['source'][0])
            c.aspect = (h/(2*meta['source'][1]))/c.focal
            c.ppx, c.ppy = w/2, h/2
            c.R = (flip @ origin.T @ np.array(meta['axes']).reshape(3, 3) @ flip).astype(np.float32)
            cameras.append(c)
    else:
        ok, cameras = cv.detail_HomographyBasedEstimator().apply(features, matches, None)
        if not ok:
            raise ValueError('Homography estimation failed')
        for c in cameras:
            c.R = c.R.astype(np.float32)
    if focal35 is not None:
        for im, c in zip([images[i] for i in indices], cameras):
            h, w = im.shape[:2]
            # Caller-supplied EXIF equivalent, converted by image diagonal.
            c.focal = math.hypot(w, h)*focal35/math.hypot(36, 24)
            c.aspect = 1.0
            c.ppx, c.ppy = w/2, h/2
    if use_gyro and adjustment in ('prior', 'validated'):
        from scipy.optimize import least_squares
        from scipy.spatial.transform import Rotation
        # Desktop diagnostic: fixed camera-center model, bounded pose corrections,
        # shared focal scale. This is NOT a ready-made SDK or a production solver.
        base = np.array([c.R for c in cameras])
        pairs = []
        n = len(features)
        for i in range(n):
            for j in range(i+1, n):
                m = matches[i*n+j]
                if m.num_inliers < 12:
                    continue
                keep = np.asarray(m.getInliers()).astype(bool).ravel()
                good = [v for v,k in zip(m.getMatches(),keep) if k]
                a = np.array([features[i].getKeypoints()[v.queryIdx].pt for v in good])
                b = np.array([features[j].getKeypoints()[v.trainIdx].pt for v in good])
                if len(a) > 150:
                    ix = np.linspace(0,len(a)-1,150).astype(int); a=a[ix]; b=b[ix]
                pairs.append((i,j,a,b))
        if not pairs:
            raise ValueError('No reliable feature pairs for bounded pose fitting')
        def unpack(x):
            delta = np.vstack([np.zeros(3), x[:-1].reshape(-1,3)])
            return Rotation.from_rotvec(delta).as_matrix() @ base, np.exp(x[-1])
        all_pairs = pairs
        test_pairs = [(i,j,a[::5],b[::5]) for i,j,a,b in pairs]
        if adjustment == "validated":
            pairs = [(i,j,a[np.arange(len(a))%5 != 0],b[np.arange(len(b))%5 != 0]) for i,j,a,b in pairs]
        def residual(x):
            rotations, scale = unpack(x)
            errors = []
            for i,j,a,b in pairs:
                bearings = []
                for k,pts in ((i,a),(j,b)):
                    c = cameras[k]
                    rays = np.column_stack([(pts[:,0]-c.ppx)/(c.focal*scale),
                                            (pts[:,1]-c.ppy)/(c.focal*c.aspect*scale), np.ones(len(pts))])
                    rays /= np.linalg.norm(rays,axis=1)[:,None]
                    bearings.append(rays @ rotations[k].T)
                errors.extend(((bearings[0]-bearings[1])*200).ravel())
            # Nonzero gyro prior prevents disconnected/weak regions drifting freely.
            errors.extend(x[:-1]*30)
            errors.append(x[-1]*3)
            return np.array(errors)
        x = np.zeros((len(cameras)-1)*3+1)
        initial_residual = float(np.median(np.abs(residual(x))))
        solution = least_squares(residual, x, loss='soft_l1', f_scale=2,
                                 bounds=(np.r_[np.full(len(x)-1,-.15),np.log(.7)],
                                         np.r_[np.full(len(x)-1,.15),np.log(2.5)]),
                                 max_nfev=50)
        rotations, scale = unpack(solution.x)
        print('prior fit', {'pairs':len(pairs), 'focal_scale':scale, 'initial_median':initial_residual,
                            'median_residual':float(np.median(np.abs(residual(solution.x))))}, flush=True)
        def pixel_errors(x, sample_pairs):
            rr, ss = unpack(x)
            errors = []
            for i,j,a,b in sample_pairs:
                ci,cj = cameras[i],cameras[j]
                rays = np.column_stack([(a[:,0]-ci.ppx)/(ci.focal*ss),
                                        (a[:,1]-ci.ppy)/(ci.focal*ci.aspect*ss), np.ones(len(a))])
                rays = rays @ (rr[j].T @ rr[i]).T
                good = rays[:,2] > 1e-4
                predicted = np.column_stack([rays[:,0]/np.maximum(rays[:,2],1e-4)*cj.focal*ss+cj.ppx,
                                              rays[:,1]/np.maximum(rays[:,2],1e-4)*cj.focal*cj.aspect*ss+cj.ppy])
                errors.extend(np.where(good,np.linalg.norm(predicted-b,axis=1),1e4))
            return dict(median=float(np.median(errors)),p90=float(np.percentile(errors,90)),
                        below_3px=float(np.mean(np.array(errors)<3)), count=len(errors))
        adjacency = {i:set() for i in range(n)}
        for i,j,_,_ in all_pairs:
            adjacency[i].add(j); adjacency[j].add(i)
        components = []
        unseen = set(range(n))
        while unseen:
            todo = [min(unseen)]; group = set()
            while todo:
                i = todo.pop()
                if i in group: continue
                group.add(i); todo.extend(adjacency[i]-group)
            unseen -= group; components.append(sorted(group))
        evidence = dict(feature_components=components,
                        unverified_frames=[i for i in range(n) if not adjacency[i]],
                        focal_scale=float(scale), held_out=adjustment == 'validated',
                        before=pixel_errors(x,test_pairs), after=pixel_errors(solution.x,test_pairs),
                        pose_bound_hits=int(np.sum(np.abs(solution.x[:-1])>.149)),
                        feature_incident_frames=sorted(set(k for i,j,_,_ in all_pairs for k in (i,j))),
                        optimizer_success=bool(solution.success))
        if diagnostics is not None: diagnostics.update(evidence)
        print('geometry evidence',evidence,flush=True)
        for c,r in zip(cameras,rotations):
            c.R = r.astype(np.float32); c.focal *= scale
    elif adjustment != 'none':
        adjuster = (cv.detail_BundleAdjusterRay() if adjustment == 'ray'
                    else cv.detail_BundleAdjusterReproj())
        adjuster.setConfThresh(confidence)
        adjuster.setTermCriteria((cv.TERM_CRITERIA_COUNT + cv.TERM_CRITERIA_EPS, 100, 1e-5))
        mask = np.zeros((3, 3), np.uint8)
        if not use_gyro and focal35 is None:
            mask[0, 0] = 1  # refine focal only; keep image center and pixel aspect
        adjuster.setRefinementMask(mask)
        ok, cameras = adjuster.apply(features, matches, cameras)
        if not ok:
            raise ValueError('Bundle adjustment failed')
    # Remove arbitrary global rotation so all results reference the first camera.
    origin = cameras[0].R.copy()
    for c in cameras:
        if not np.isfinite(c.focal) or not 20 < c.focal < max(images[0].shape)*10:
            raise ValueError('Implausible fitted focal')
        c.R = (origin.T @ c.R).astype(np.float32)
    return indices, cameras


def compose(images, cameras, out, target=None, compensate=True):
    corners, warped, masks = [], [], []
    if target is not None:
        h, w = 1080, 720
        Kout = np.array([[w/(2*target[0]*1.15), 0, w/2],
                         [0, h/(2*target[1]*1.15), h/2], [0, 0, 1.]])
    else:
        warper = cv.PyRotationWarper('spherical', min(600., float(np.median([c.focal for c in cameras]))))
    for im, c in zip(images, cameras):
        mask = np.full(im.shape[:2], 255, np.uint8)
        if target is not None:
            H = Kout @ c.R @ np.linalg.inv(c.K())
            image = cv.warpPerspective(im, H, (w, h))
            mask = cv.warpPerspective(mask, H, (w, h), flags=cv.INTER_NEAREST)
            yy,xx = np.mgrid[:h,:w]
            inverse = np.linalg.inv(H)
            mask[(inverse[2,0]*xx+inverse[2,1]*yy+inverse[2,2]) <= 0] = 0
            corner = (0, 0)
        else:
            K = c.K().astype(np.float32)
            roi = warper.warpRoi((im.shape[1], im.shape[0]), K, c.R)
            if roi[2]*roi[3] > 12_000_000:
                raise ValueError('Unreasonable warp size')
            corner, image = warper.warp(im, K, c.R, cv.INTER_LINEAR, cv.BORDER_REFLECT)
            _, mask = warper.warp(mask, K, c.R, cv.INTER_NEAREST, cv.BORDER_CONSTANT)
        corners.append(corner); warped.append(image); masks.append(mask)
    sizes = [(im.shape[1], im.shape[0]) for im in warped]
    roi = cv.detail.resultRoi(corners=corners, sizes=sizes)
    if roi[2]*roi[3] > 16_000_000:
        raise ValueError('Unreasonable output size')
    union = np.zeros((roi[3], roi[2]), np.uint8)
    for (x, y), mask in zip(corners, masks):
        patch = union[y-roi[1]:y-roi[1]+mask.shape[0], x-roi[0]:x-roi[0]+mask.shape[1]]
        cv.bitwise_or(patch, mask, dst=patch)
    print('warp complete', flush=True)
    # Keep support masks separate from seam ownership masks.
    exposure = cv.detail.ExposureCompensator_createDefault(
        cv.detail.ExposureCompensator_GAIN_BLOCKS if compensate else cv.detail.ExposureCompensator_NO)
    exp_images = [cv.resize(im, None, fx=.25, fy=.25) for im in warped]
    exp_masks = [cv.resize(m, (im.shape[1], im.shape[0]), interpolation=cv.INTER_NEAREST) for m, im in zip(masks, exp_images)]
    exposure.feed([(round(x*.25), round(y*.25)) for x,y in corners], exp_images, exp_masks)
    for i, im in enumerate(warped):
        exposure.apply(i, corners[i], im, masks[i])
    print('exposure complete', flush=True)
    seam = cv.detail_GraphCutSeamFinder('COST_COLOR_GRAD')
    seam_scale = .25
    small_images = [cv.resize(im, None, fx=seam_scale, fy=seam_scale).astype(np.float32) for im in warped]
    small_masks = [cv.resize(m, (im.shape[1], im.shape[0]), interpolation=cv.INTER_NEAREST) for m, im in zip(masks, small_images)]
    small_corners = [(round(x*seam_scale), round(y*seam_scale)) for x,y in corners]
    selected_masks = seam.find(small_images, small_corners, small_masks)
    seam_masks = []
    for m, original in zip(selected_masks, masks):
        m = m.get() if isinstance(m, cv.UMat) else m
        expanded = cv.resize(cv.dilate(m, None), (original.shape[1], original.shape[0]), interpolation=cv.INTER_NEAREST)
        seam_masks.append(cv.bitwise_and(expanded, original))
    print('seam complete', flush=True)
    blender = cv.detail_MultiBandBlender()
    blender.setNumBands(5)
    blender.prepare(roi)
    for im, mask, corner in zip(warped, seam_masks, corners):
        blender.feed(im.astype(np.int16), mask, corner)
    result, support = blender.blend(None, None)
    result = np.clip(result, 0, 255).astype(np.uint8)
    cv.imwrite(str(out/'panorama.jpg'), result)
    cv.imwrite(str(out/'support.png'), union)
    thumb = cv.resize(result, (round(result.shape[1]*min(1, 1200/max(result.shape[:2]))),
                               round(result.shape[0]*min(1, 1200/max(result.shape[:2])))))
    cv.imwrite(str(out/'preview.jpg'), thumb)
    return dict(output_shape=list(result.shape), support_fraction=float(np.mean(union > 0)),
                coverage_meaning='expanded target' if target else 'output bounding rectangle',
                focal_pixels=[float(c.focal) for c in cameras])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('zip', type=Path); p.add_argument('out', type=Path)
    p.add_argument('--edge', type=int, default=1800)
    p.add_argument('--gyro-feature', choices=['sift','orb','akaze'], default='sift')
    p.add_argument('--focal35', type=float, help='EXIF equivalent focal for known-reproj')
    p.add_argument('--keyframes', type=int, default=18)
    p.add_argument('--cases', default='sift-ray,sift-reproj,orb-ray,akaze-ray,gyro-none,gyro-reproj')
    args = p.parse_args()
    if args.edge < 64 or args.keyframes < 2:
        p.error('edge >= 64 and keyframes >= 2 required')
    if 'known-' in args.cases and (args.focal35 is None or args.focal35 <= 0):
        p.error('--focal35 is required for known-focal cases')
    cv.setNumThreads(4)
    images, meta, names, original_count = read_frames(args.zip, args.edge, args.keyframes)
    reports = []
    for case in args.cases.split(','):
        gyro = case.startswith('gyro')
        if gyro and meta[0] is None:
            continue
        feature, adjustment = case.split('-')
        folder = args.out/case; folder.mkdir(parents=True, exist_ok=True)
        start = time.perf_counter()
        report = dict(case=case, opencv=cv.__version__, original_count=original_count,
                      selected_inputs=names, edge=args.edge, focal35=args.focal35, gyro_feature=args.gyro_feature, quality_verified=False)
        cv.setRNGSeed(0)
        try:
            geometry = {}
            indices, cameras = fit(images, meta, args.gyro_feature if gyro else ('sift' if feature == 'known' else feature), adjustment, .7, gyro, args.focal35 if feature == 'known' else None, geometry)
            print(case+' fit complete', flush=True)
            report['geometry_diagnostics'] = geometry
            report.update(participating=[names[i] for i in indices], all_selected_used=len(indices)==len(images))
            report.update(compose([images[i] for i in indices], cameras, folder,
                                  meta[0]['target'] if gyro else None))
            report['status'] = 'composed_requires_visual_review'
        except Exception as e:
            report.update(status='failed', error=str(e))
        report['seconds'] = time.perf_counter()-start
        (folder/'report.json').write_text(json.dumps(report, indent=2), encoding='utf8')
        reports.append(report)
        print(json.dumps(report), flush=True)
    (args.out/'summary.json').write_text(json.dumps(reports, indent=2), encoding='utf8')


if __name__ == '__main__':
    main()
