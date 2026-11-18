"""Compare inertial and visual rotation on real candidate frames in a fixed target crop."""
import argparse
import json
import time
import zipfile
from pathlib import Path

import cv2 as cv
import numpy as np


def fit_rotation(a, b):
    u, _, v = np.linalg.svd(b.T @ a)
    return u @ np.diag([1, 1, np.linalg.det(u @ v)]) @ v


def angle(r):
    return np.degrees(np.arccos(np.clip((np.trace(r)-1)/2, -1, 1)))


def project(points, k, rotation):
    rays = np.column_stack([points, np.ones(len(points))]) @ np.linalg.inv(k).T
    pixels = rays @ rotation.T @ k.T
    return pixels[:, :2] / pixels[:, 2:]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('zip', type=Path)
    parser.add_argument('out', type=Path)
    parser.add_argument('--scale', type=float, default=1)
    args = parser.parse_args()
    args.out.mkdir(exist_ok=True, parents=True)
    cv.setNumThreads(2)
    detector = cv.SIFT_create(550, contrastThreshold=.015)
    matcher = cv.BFMatcher()
    refs = []
    records = []
    flip = np.diag([1., -1., -1.])
    start = time.perf_counter()
    with zipfile.ZipFile(args.zip) as archive:
        numbers = sorted(int(Path(n).stem) for n in archive.namelist() if n.endswith('.jpg'))
        for number in numbers:
            im = cv.imdecode(np.frombuffer(archive.read(f'{number}.jpg'), np.uint8), 1)
            m = json.loads(archive.read(f'{number}.json'))
            axes = np.array(m['axes']).reshape(3,3)
            if not records:
                origin = axes
                target = np.array(m['target'])*1.15
                h, w = 720, round(720*target[0]/target[1])
                ko = np.array([[w/(2*target[0]),0,w/2],[0,h/(2*target[1]),h/2],[0,0,1]])
                atlas = np.zeros((h,w,3),np.uint8)
                weight = np.zeros((h,w),np.float32)
                potential = weight.copy()
                yy,xx=np.mgrid[:h,:w]
            sensor = flip @ origin.T @ axes @ flip
            ih,iw=im.shape[:2]
            k = np.array([[iw/(2*m['source'][0])*args.scale,0,iw/2],
                          [0,ih/(2*m['source'][1])*args.scale,ih/2],[0,0,1]])
            def warp(r):
                H=ko @ r @ np.linalg.inv(k)
                inv=np.linalg.inv(H)
                mask=cv.warpPerspective(np.ones((ih,iw),np.uint8),H,(w,h),flags=cv.INTER_NEAREST)
                mask[inv[2,0]*xx+inv[2,1]*yy+inv[2,2]<=0]=0
                return H,mask
            _,mask=warp(sensor)
            potential=np.maximum(potential,mask)
            kp,desc=detector.detectAndCompute(cv.cvtColor(im,cv.COLOR_BGR2GRAY),None)
            points=np.float64([x.pt for x in kp]).reshape(-1,2)
            best=None
            row=dict(frame=number,angle=angle(sensor),onsite=m['accepted'])
            near=sorted(refs,key=lambda ref:angle(ref[1].T@sensor))
            for ref in near[:4]:
                idx,rs,rv,rp,rd=ref
                if desc is None or len(rd)<2:continue
                pairs=[a for a,b in matcher.knnMatch(desc,rd,k=2) if a.distance<.72*b.distance]
                if len(pairs)<16:continue
                a=points[[p.queryIdx for p in pairs]]
                b=rp[[p.trainIdx for p in pairs]]
                train=np.arange(len(a))%4!=0
                _,inliers=cv.findHomography(a[train],b[train],cv.RANSAC,3)
                if inliers is None or inliers.sum()<10:continue
                keep=inliers.ravel().astype(bool)
                def bearings(pts):
                    v=np.column_stack([pts,np.ones(len(pts))]) @ np.linalg.inv(k).T
                    return v/np.linalg.norm(v,axis=1)[:,None]
                rel=fit_rotation(bearings(a[train][keep]),bearings(b[train][keep]))
                err=np.linalg.norm(project(a[~train],k,rel)-b[~train],axis=1)
                inertial=np.linalg.norm(project(a[~train],k,rs.T@sensor)-b[~train],axis=1)
                score=np.median(err)
                if best is None or score<best[0]:
                    best=(score,rv@rel,idx,float(np.median(inertial)),len(err))
            if not records or best is not None and best[0]<4:
                rotation=sensor if not records else best[1]
                H,mask=warp(rotation)
                if desc is not None and (not refs or min(angle(x[1].T@sensor) for x in refs)>3):
                    refs.append((number,sensor,rotation,points,desc))
                score=cv.distanceTransform(mask,cv.DIST_L2,3)
                take=score>weight
                if take.any():
                    incoming=cv.warpPerspective(im,H,(w,h))
                    atlas[take]=incoming[take]
                    weight[take]=score[take]
                row['verified']=bool(records)
            if best is not None:row.update(error=float(best[0]),reference=best[2],gyroError=best[3],samples=best[4])
            records.append(row)
    elapsed=time.perf_counter()-start
    cv.imwrite(str(args.out/'panorama.jpg'),atlas)
    print('seconds',round(elapsed,2),'registered',sum(bool(x.get('verified')) for x in records),'refs',len(refs),'coverage',np.mean(weight>0),'gyroSupport',np.mean(potential>0))
    for lo,hi in [(0,15),(15,30),(30,180)]:
        group=[r for r in records if lo<=r['angle']<hi and 'error'in r]
        if group: print(lo,hi,len(group),'visual',np.median([r['error'] for r in group]),'gyro',np.median([r['gyroError'] for r in group]))
    (args.out/'evidence.json').write_text(json.dumps(records))

if __name__=='__main__':main()
