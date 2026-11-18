"""Compare held-out local homography errors with gyro errors on captured pairs.

Training RANSAC is performed only on training matches. Held-out descriptors
can contain false matches, so report medians, tails and counts, never a pass.
"""
import argparse
import json
from pathlib import Path
import cv2 as cv
import numpy as np
from panorama_offline_matrix import read_frames


def summary(values):
    values=np.asarray(values)
    return dict(points=len(values),median=float(np.median(values)),
                p90=float(np.percentile(values,90)),below3=float(np.mean(values<3)))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('zip',type=Path)
    parser.add_argument('out',type=Path)
    parser.add_argument('--window',type=int,default=6,
                        help='Search this many later frames; 0 searches all pairs')
    args=parser.parse_args()
    images,meta,names,_=read_frames(args.zip,720,61)
    cv.setRNGSeed(0);cv.setNumThreads(4)
    detector=cv.AKAZE_create()
    features=[detector.detectAndCompute(im,None) for im in images]
    matcher=cv.BFMatcher(cv.NORM_HAMMING)
    results=[];flip=np.diag([1.,-1.,-1.])
    for i in range(len(images)):
        for j in range(i+1,min(i+1+args.window,len(images)) if args.window else len(images)):
            ka,da=features[i];kb,db=features[j]
            if da is None or db is None:continue
            matches=[a for pair in matcher.knnMatch(da,db,k=2)
                     if len(pair)==2 for a,b in [pair] if a.distance<.7*b.distance]
            if len(matches)<25:continue
            a=np.float64([ka[m.queryIdx].pt for m in matches])
            b=np.float64([kb[m.trainIdx].pt for m in matches])
            held=np.arange(len(a))%5==0
            hom,mask=cv.findHomography(a[~held],b[~held],cv.RANSAC,3)
            if hom is None or mask.sum()<15:continue
            affine,affine_mask=cv.estimateAffinePartial2D(a[~held],b[~held],method=cv.RANSAC,ransacReprojThreshold=3)
            affine_h=np.vstack([affine,[0,0,1.]]) if affine is not None else None
            affine_error=summary(np.linalg.norm(cv.perspectiveTransform(a[held,None,:],affine_h).reshape(-1,2)-b[held],axis=1)) if affine_h is not None else None
            pred=cv.perspectiveTransform(a[held,None,:],hom).reshape(-1,2)
            local=np.linalg.norm(pred-b[held],axis=1)
            h,w=images[i].shape[:2]
            inliers=mask.ravel().astype(bool)
            support_a=cv.contourArea(cv.convexHull(a[~held][inliers].astype(np.float32)))/(w*h)
            support_b=cv.contourArea(cv.convexHull(b[~held][inliers].astype(np.float32)))/(w*h)
            corners=np.float64([[0,0,1],[w,0,1],[w,h,1],[0,h,1]])
            depths=corners@hom[2]
            crosses_horizon=bool(depths.min()*depths.max()<=0)
            fx=w/(2*meta[i]['source'][0])*1.3361322
            fy=h/(2*meta[i]['source'][1])*1.3361322
            k=np.array([[fx,0,w/2],[0,fy,h/2],[0,0,1.]])
            ri=flip@np.array(meta[i]['axes']).reshape(3,3)@flip
            rj=flip@np.array(meta[j]['axes']).reshape(3,3)@flip
            gh=k@rj.T@ri@np.linalg.inv(k)
            pred=cv.perspectiveTransform(a[held,None,:],gh).reshape(-1,2)
            gyro=np.linalg.norm(pred-b[held],axis=1)
            results.append(dict(i=names[i],j=names[j],matches=len(matches),
                                homography=hom.tolist(),
                                similarity=affine_h.tolist() if affine_h is not None else None,
                                similarityError=affine_error,
                                supportFraction=min(support_a,support_b),
                                crossesHorizon=crosses_horizon,
                                trainingInliers=int(mask.sum()),
                                localHomography=summary(local),gyro=summary(gyro)))
    remaining=set(names);components=[]
    edges=[(p['i'],p['j']) for p in results if p['localHomography']['median']<3]
    while remaining:
        component={min(remaining)};remaining-=component;queue=list(component)
        while queue:
            node=queue.pop()
            for a,b in edges:
                other=b if a==node else a if b==node else None
                if other in remaining:
                    remaining.remove(other);component.add(other);queue.append(other)
        components.append(sorted(component))
    output=dict(pairs=results,components=components,limitations='Same scene, fixed descriptive focal scale from earlier audit; homographies cannot establish globally consistent rotations or exclude parallax.')
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(output,indent=2))
    print('Component sizes:',sorted(map(len,components),reverse=True))
    for model in ('localHomography','gyro'):
        print(model,'pairs',len(results),'median of pair medians',
              np.median([p[model]['median'] for p in results]),
              'pairs median <3px',sum(p[model]['median']<3 for p in results))


if __name__=='__main__':main()
