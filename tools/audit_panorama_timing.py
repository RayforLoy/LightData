"""Fit shared focal/time offsets to private recorded frames; never a device calibration.

Uses all recorded pose samples for interpolation, rejects interpolation across gaps,
and holds out every fifth RANSAC inlier from the numerical fit. Requires scipy.
"""
import argparse
import json
from pathlib import Path
import zipfile
import cv2 as cv
import numpy as np
from scipy.optimize import least_squares
from scipy.spatial.transform import Rotation, Slerp
from panorama_offline_matrix import read_frames


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('zip', type=Path)
    parser.add_argument('out', type=Path)
    args = parser.parse_args()
    images, meta, names, _ = read_frames(args.zip, 720, 18)
    with zipfile.ZipFile(args.zip) as archive:
        records = [json.loads(archive.read(n)) for n in archive.namelist()
                   if n.endswith('.json') and n != 'manifest.json']
    records.sort(key=lambda r:r['timeMs'])
    t0 = records[0]['timeMs']
    times = np.array([(r['timeMs']-t0)/1000 for r in records])
    flip = np.diag([1.,-1.,-1.])
    rotations = np.array([flip @ np.array(r['axes']).reshape(3,3) @ flip for r in records])
    interpolate = Slerp(times, Rotation.from_matrix(rotations))
    selected_times = np.array([(r['timeMs']-t0)/1000 for r in meta])
    # The complete +/-250ms range must lie in a continuous recorded segment.
    valid = []
    for t in selected_times:
        left, right = np.searchsorted(times, [t-.25,t+.25])
        valid.append(t-.25 >= times[0] and t+.25 <= times[-1] and
                     np.all(np.diff(times[max(0,left-1):min(len(times),right+1)]) <= .5))
    cv.setRNGSeed(0); cv.setNumThreads(4)
    features = [cv.detail.computeImageFeatures2(cv.AKAZE_create(), im) for im in images]
    matcher = cv.detail_BestOf2NearestMatcher(False,.3)
    matches = matcher.apply2(features); matcher.collectGarbage()
    pairs=[]
    for i in range(len(images)):
        for j in range(i+1,len(images)):
            m=matches[i*len(images)+j]
            if not valid[i] or not valid[j] or m.num_inliers<12: continue
            keep=np.asarray(m.getInliers()).ravel().astype(bool)
            good=[v for v,k in zip(m.getMatches(),keep) if k]
            a=np.array([features[i].getKeypoints()[v.queryIdx].pt for v in good])
            b=np.array([features[j].getKeypoints()[v.trainIdx].pt for v in good])
            pairs.append((i,j,a,b))
    if not pairs: raise ValueError('No pairs with continuous pose support')

    def errors(x, heldout):
        scale, delay=np.exp(x[0]),x[1]
        k1, k2 = (x[2], x[3]) if len(x) >= 4 else (0., 0.)
        # Unsafe frames cannot participate; clipping here only handles unused frames.
        rr=interpolate(np.clip(selected_times+delay,times[0],times[-1])).as_matrix()
        result=[]
        for i,j,a,b in pairs:
            select=np.arange(len(a))%5==0
            if not heldout: select=~select
            a,b=a[select],b[select]
            h,w=images[i].shape[:2];hj,wj=images[j].shape[:2]
            fx,fy=w/(2*meta[i]['source'][0])*scale,h/(2*meta[i]['source'][1])*scale
            fxj,fyj=wj/(2*meta[j]['source'][0])*scale,hj/(2*meta[j]['source'][1])*scale
            camera=np.array([[fx,0,w/2],[0,fy,h/2],[0,0,1.]])
            normalized=cv.undistortPoints(a.reshape(-1,1,2),camera,
                                          np.array([k1,k2,0.,0.,0.])).reshape(-1,2)
            rays=np.column_stack([normalized,np.ones(len(a))])
            rays=rays@(rr[j].T@rr[i]).T
            z=np.maximum(rays[:,2],1e-4)
            xy=rays[:,:2]/z[:,None]
            r2=np.sum(xy*xy,axis=1)
            xy*= (1+k1*r2+k2*r2*r2)[:,None]
            pred=xy*np.array([fxj,fyj])+np.array([wj/2,hj/2])
            result.extend(pred-b)
        return np.array(result)

    def metrics(x):
        e=np.linalg.norm(errors(x,True),axis=1)
        return dict(median_px=float(np.median(e)),p90_px=float(np.percentile(e,90)),
                    below_3px=float(np.mean(e<3)),points=len(e))
    report=dict(pairs=len(pairs), excluded_frame_names=[n for n,v in zip(names,valid) if not v],
                baseline=metrics([0.,0.]), limitations='Same-scene inliers; pose samples are sparse, not original IMU stream.')
    for label,free_time in [('focal_only',False),('focal_and_time',True)]:
        if free_time:
            fit=least_squares(lambda x:errors(x,False).ravel(),[0.,0.],
                              bounds=([np.log(.7),-.25],[np.log(2.5),.25]),loss='soft_l1',f_scale=3)
            x=fit.x
        else:
            fit=least_squares(lambda x:errors([x[0],0.],False).ravel(),[0.],
                              bounds=([np.log(.7)],[np.log(2.5)]),loss='soft_l1',f_scale=3)
            x=[fit.x[0],0.]
        report[label]=dict(focal_scale=float(np.exp(x[0])),delay_ms=float(x[1]*1000),
                           success=bool(fit.success),holdout=metrics(x))
    start=report['focal_and_time']
    fit=least_squares(lambda x:errors(x,False).ravel(),
                      [np.log(start['focal_scale']),start['delay_ms']/1000,0.,0.],
                      bounds=([np.log(.7),-.25,-.25,-.1],[np.log(2.5),.25,.25,.1]),
                      loss='soft_l1',f_scale=3,max_nfev=150)
    report['focal_time_radial']=dict(focal_scale=float(np.exp(fit.x[0])),
        delay_ms=float(fit.x[1]*1000), k1=float(fit.x[2]),k2=float(fit.x[3]),
        success=bool(fit.success),holdout=metrics(fit.x))
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(report,indent=2),encoding='utf8')
    print(json.dumps(report,indent=2))


if __name__=='__main__': main()
