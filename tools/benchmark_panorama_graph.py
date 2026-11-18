"""Offline keyframe graph. Cached features stay on original images at preview scale."""
import argparse
import json
import time
import zipfile
from pathlib import Path
import cv2 as cv
import numpy as np
from scipy.spatial.transform import Rotation
from benchmark_keyframe_rotation import angle, fit_rotation, project

def bridge_rotation(image_a,image_b,prior,k):
    """Recover a missing anchor edge using gyro-bounded mutual descriptor searches."""
    from scipy.spatial import cKDTree
    a,da=image_a;b,db=image_b
    if da is None or db is None:return None
    def search(a,da,b,db,rot):
        predicted=project(a,k,rot)
        # Limit descriptor comparisons to a small cone around the inertial prediction.
        neighbors=cKDTree(b).query_ball_point(predicted,.36*max(k[0,2],k[1,2]))
        matches={}
        for i,indices in enumerate(neighbors):
            if len(indices)<2:continue
            dist=np.linalg.norm(db[indices]-da[i],axis=1)
            ix=np.argpartition(dist,1)[:2]
            if dist[ix[0]]<.83*dist[ix[1]]:matches[i]=indices[ix[0]]
        return matches
    forward=search(a,da,b,db,prior)
    backward=search(b,db,a,da,prior.T)
    pairs=[(i,j) for i,j in forward.items() if backward.get(j)==i]
    if len(pairs)<18:return None
    a=a[[i for i,j in pairs]];b=b[[j for i,j in pairs]]
    def rays(pts):
        v=np.column_stack([pts,np.ones(len(pts))])@np.linalg.inv(k).T
        return v/np.linalg.norm(v,axis=1)[:,None]
    ra=rays(a);rb=rays(b)
    rng=np.random.default_rng(19)
    train=np.arange(len(a))%3!=0; data=np.flatnonzero(train)
    best=np.zeros(len(a),bool)
    for _ in range(160):
        ix=rng.choice(data,3,replace=False)
        r=fit_rotation(ra[ix],rb[ix])
        if angle(r.T@prior)>12:continue
        inlier=(np.linalg.norm(project(a,k,r)-b,axis=1)<5)&train
        if inlier.sum()>best.sum():best=inlier
    if best.sum()<9:return None
    r=fit_rotation(ra[best],rb[best])
    valid=(np.linalg.norm(project(a,k,r)-b,axis=1)<6)&~train
    if valid.sum()<4 or cv.contourArea(cv.convexHull(a[best|valid]))<k[0,2]*k[1,2]*.09:return None
    return r


def flow_correspondences(gray_a, gray_b, predicted):
    """Track on a common perspective so a large camera turn is not a large LK displacement."""
    height, width = gray_b.shape
    aligned = cv.warpPerspective(gray_a, predicted, (width, height))
    support = cv.warpPerspective(np.full_like(gray_a, 255), predicted, (width, height))
    support = cv.erode(support, np.ones((19, 19), np.uint8))
    points = cv.goodFeaturesToTrack(aligned, 200, .014, 9, mask=support)
    if points is None or len(points) < 16:
        return None
    settings = dict(winSize=(21,21), maxLevel=3)
    matched, valid, _ = cv.calcOpticalFlowPyrLK(aligned, gray_b, points, None, **settings)
    back, reverse, _ = cv.calcOpticalFlowPyrLK(gray_b, aligned, matched, None, **settings)
    close = np.linalg.norm(back-points,axis=2).ravel() < .9
    keep = (valid.ravel()>0) & (reverse.ravel()>0) & close
    # Enforce multiple independent image neighborhoods before trusting a planar fit.
    p = points[keep].reshape(-1,2)
    if len(p)<16 or cv.contourArea(cv.convexHull(p)) < width*height*.035:
        return None
    return cv.perspectiveTransform(points[keep],np.linalg.inv(predicted)).reshape(-1,2), matched[keep].reshape(-1,2)


def bundle(rotations,edges):
    initial=np.array(rotations)
    rot=initial.copy()
    for _ in range(10):
        matrix=[];residual=[]
        for i,j,rel,quality in edges:
            delta=Rotation.from_matrix(rot[j] @ rel @ rot[i].T).as_rotvec()
            sigma=np.radians(1.5)
            weight=quality*np.sqrt(min(1,sigma/max(np.linalg.norm(delta),1e-8)))
            row=np.zeros(len(rot));row[i]=weight;row[j]=-weight
            matrix.append(row);residual.append(delta*weight)
        for i in range(len(rot)):
            weight=10 if i==0 else .12
            row=np.zeros(len(rot));row[i]=weight
            matrix.append(row);residual.append(weight*Rotation.from_matrix(initial[i]@rot[i].T).as_rotvec())
        step=np.linalg.lstsq(matrix,residual,rcond=None)[0]
        rot=Rotation.from_rotvec(step).as_matrix()@rot
        if np.max(abs(step))<.0001:break
    return rot[0].T@rot

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('zip',type=Path);parser.add_argument('out',type=Path)
    parser.add_argument('--spacing',type=float,default=6)
    parser.add_argument('--feature-edge',type=int,default=540)
    parser.add_argument('--neighbors',type=int,default=7)
    args=parser.parse_args();args.out.mkdir(exist_ok=True,parents=True)
    cv.setNumThreads(2);cv.setRNGSeed(0)
    start=time.perf_counter()
    with zipfile.ZipFile(args.zip) as z:
        ids=sorted(int(Path(n).stem) for n in z.namelist() if n.endswith('.jpg'))
        rows=[json.loads(z.read(f'{i}.json')) for i in ids]
        flip=np.diag([1.,-1.,-1.]);origin=np.array(rows[0]['axes']).reshape(3,3)
        rots=np.array([flip@origin.T@np.array(r['axes']).reshape(3,3)@flip for r in rows])
        keep=[0]
        for i in range(1,len(ids)):
            if min(angle(rots[j].T@rots[i]) for j in keep)>args.spacing:keep.append(i)
        ims=[cv.imdecode(np.frombuffer(z.read(f'{ids[i]}.jpg'),np.uint8),1) for i in keep]
    rs=rots[keep]; h,w=ims[0].shape[:2]
    target=rows[0]['target'];source=rows[0]['source']
    k=np.array([[w/2/source[0]*1.04,0,w/2],[0,h/2/source[1]*1.04,h/2],[0,0,1.]])
    size=args.feature_edge/max(h,w)
    detector=cv.SIFT_create(650,contrastThreshold=.015)
    features=[]; grays=[]
    for im in ims:
        gray=cv.resize(cv.cvtColor(im,cv.COLOR_BGR2GRAY),None,fx=size,fy=size,interpolation=cv.INTER_AREA)
        grays.append(gray)
        kp,desc=detector.detectAndCompute(gray,None)
        features.append((np.float64([p.pt for p in kp]).reshape(-1,2)/size,desc))
    edges=[];holdout=[];seen=set();matcher=cv.BFMatcher()
    def rays(pts):
        v=np.column_stack([pts,np.ones(len(pts))])@np.linalg.inv(k).T
        return v/np.linalg.norm(v,axis=1)[:,None]
    for i,(pa,da) in enumerate(features):
        near=np.argsort([angle(r.T@rs[i]) for r in rs])[1:args.neighbors+1]
        # Revisit the center directly as well as following adjacent viewpoints.
        # Angular neighbors alone can strand the anchor in a sparsely sampled scan.
        for j in dict.fromkeys([*near, 0, max(0,i-1)]):
            if i == j: continue
            pair=tuple(sorted((i,int(j))))
            if pair in seen:continue
            seen.add(pair);pb,db=features[j]
            good=[] if da is None or db is None or len(db)<2 else [
                m for m,n in matcher.knnMatch(da,db,k=2) if m.distance<.76*n.distance]
            def sources():
                if len(good)>=14:
                    yield pa[[m.queryIdx for m in good]],pb[[m.trainIdx for m in good]]
                ks=np.diag([size,size,1.])@k
                tracked=flow_correspondences(grays[i],grays[j],ks@rs[j].T@rs[i]@np.linalg.inv(ks))
                if tracked is not None:
                    yield tracked[0]/size,tracked[1]/size
            for a,b in sources():
                split=np.arange(len(a))%6
                train=split>=2
                validate=split==1
                test=split==0
                _,mask=cv.findHomography(a[train],b[train],cv.RANSAC,3)
                if mask is None or mask.sum()<9:continue
                valid=mask.ravel().astype(bool)
                rel=fit_rotation(rays(a[train][valid]),rays(b[train][valid]))
                err=np.linalg.norm(project(a[validate],k,rel)-b[validate],axis=1)
                if np.median(err)>7 or angle(rel.T@rs[j].T@rs[i])>12:continue
                edges.append((i,int(j),rel,1/max(1,np.median(err)/2)))
                holdout.append((i,int(j),a[test],b[test]))
                break
    # A missing initial transition can leave a strong local component disconnected.
    def reachable():
        found={0}
        while True:
            previous=len(found)
            for i,j,_,_ in edges:
                if i in found or j in found:found.update((i,j))
            if len(found)==previous:return found
    connected=reachable()
    bridges=[]
    if len(connected)==1 and len(rs)>1:
        detector=cv.SIFT_create(1500,contrastThreshold=.009)
        def full_features(im):
            pts,desc=detector.detectAndCompute(cv.cvtColor(im,cv.COLOR_BGR2GRAY),None)
            return np.float32([p.pt for p in pts]).reshape(-1,2),desc
        center_features=full_features(ims[0])
        for j in np.argsort([angle(r.T@rs[0]) for r in rs])[1:]:
            if angle(rs[j].T@rs[0])>65:continue
            rel=bridge_rotation(center_features,full_features(ims[j]),rs[j].T@rs[0],k)
            if rel is None:continue
            edges.append((0,int(j),rel,1))
            bridges.append(ids[keep[j]])
            if len(bridges)>=3:break
    opt=bundle(rs,edges)
    def metrics(rot):
        errs=np.concatenate([np.empty(0)]+[np.linalg.norm(project(a,k,rot[j].T@rot[i])-b,axis=1) for i,j,a,b in holdout])
        return [round(np.median(errs),2),round(np.percentile(errs,90),2)] if len(errs) else None
    H=720
    W=round(H*target[0]/target[1])
    ko=np.array([[W/2/(target[0]*1.15),0,W/2],[0,H/2/(target[1]*1.15),H/2],[0,0,1.]])
    yy,xx=np.mgrid[:H,:W];warped=[];masks=[];qualities=[]
    connected={0}
    while True:
        before=len(connected)
        for i,j,_,_ in edges:
            if i in connected or j in connected:connected.update([i,j])
        if before==len(connected):break
    for i in sorted(connected):
        mat=ko@opt[i]@np.linalg.inv(k);inv=np.linalg.inv(mat)
        mask=cv.warpPerspective(np.full((h,w),255,np.uint8),mat,(W,H),flags=cv.INTER_NEAREST)
        mask[inv[2,0]*xx+inv[2,1]*yy+inv[2,2]<=0]=0
        warped.append(cv.warpPerspective(ims[i],mat,(W,H)))
        masks.append(mask)
        qualities.append(cv.distanceTransform(mask,cv.DIST_L2,3))
    best=np.zeros((H,W),np.float32);selected=[]
    for _ in range(14):
        gains=[np.maximum(0,np.minimum(q,40)-best).sum() if i not in selected else 0 for i,q in enumerate(qualities)]
        i=int(np.argmax(gains))
        if gains[i]==0:break
        selected.append(i);best=np.maximum(best,np.minimum(qualities[i],40))
    warp=[warped[i] for i in selected];mask=[masks[i] for i in selected]
    sm=[cv.resize(im,None,fx=.4,fy=.4,interpolation=cv.INTER_AREA).astype(np.float32) for im in warp]
    mm=[cv.resize(m,None,fx=.4,fy=.4,interpolation=cv.INTER_NEAREST) for m in mask]
    seam=cv.detail_GraphCutSeamFinder('COST_COLOR_GRAD').find(sm,[(0,0)]*len(sm),mm)
    blend=cv.detail_MultiBandBlender();blend.setNumBands(4);blend.prepare((0,0,W,H))
    for im,m,s in zip(warp,mask,seam):
        owner=cv.resize(cv.dilate(s.get(),None),(W,H),interpolation=cv.INTER_NEAREST)&m
        blend.feed(im.astype(np.int16),owner,(0,0))
    result,support=blend.blend(None,None)
    cv.imwrite(str(args.out/'panorama.jpg'),np.uint8(np.clip(result,0,255)))
    union=np.logical_or.reduce(mask)
    margin_x=round(W*(1-1/1.15)/2)
    margin_y=round(H*(1-1/1.15)/2)
    report=dict(anchorBridges=bridges,rawCoverage=float(union.mean()),targetCoverage=float(union[margin_y:H-margin_y,margin_x:W-margin_x].mean()),total=len(ids),keys=len(keep),connected=len(connected),edges=len(edges),selected=[ids[keep[sorted(connected)[i]]] for i in selected],coverage=np.mean(support>0),gyro=metrics(rs),optimized=metrics(opt),seconds=time.perf_counter()-start)
    print(report);(args.out/'report.json').write_text(json.dumps(report))

if __name__=='__main__':main()
