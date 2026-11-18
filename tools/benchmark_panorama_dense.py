"""Desktop experiment: bounded DIS flow after gyro projection.

Not a complete SDK or a calibration. Geometry remains on the fixed target
canvas; cosmetic merging never provides exposure measurements.
"""
import argparse
import json
import time
from pathlib import Path
import cv2 as cv
import numpy as np
from panorama_offline_matrix import read_frames


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('zip',type=Path);p.add_argument('out',type=Path)
    p.add_argument('--focal-scale',type=float,default=1.)
    p.add_argument('--no-flow',action='store_true')
    p.add_argument('--merge',choices=['average','seam'],default='average')
    args=p.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    cv.setNumThreads(4);cv.setRNGSeed(0)
    images,meta,names,total=read_frames(args.zip,720,61)
    h,w=720,480;yy,xx=np.mgrid[:h,:w].astype(np.float32)
    target=meta[0]['target']
    kout=np.array([[w/(2*target[0]*1.15),0,w/2],[0,h/(2*target[1]*1.15),h/2],[0,0,1.]])
    origin=np.array(meta[0]['axes']).reshape(3,3);flip=np.diag([1.,-1.,-1.])
    atlas=np.zeros((h,w,3),np.float32);weight=np.zeros((h,w),np.float32)
    dis=cv.DISOpticalFlow_create(cv.DISOPTICAL_FLOW_PRESET_MEDIUM)
    report=[];start=time.perf_counter()
    for name,im,m in zip(names,images,meta):
        tick=time.perf_counter();ih,iw=im.shape[:2]
        k=np.array([[iw/(2*m['source'][0])*args.focal_scale,0,iw/2],
                    [0,ih/(2*m['source'][1])*args.focal_scale,ih/2],[0,0,1.]])
        r=flip@origin.T@np.array(m['axes']).reshape(3,3)@flip
        hom=kout@r@np.linalg.inv(k)
        warped=cv.warpPerspective(im,hom,(w,h))
        mask=cv.warpPerspective(np.full((ih,iw),255,np.uint8),hom,(w,h),flags=cv.INTER_NEAREST)
        inverse=np.linalg.inv(hom)
        mask[inverse[2,0]*xx+inverse[2,1]*yy+inverse[2,2]<=0]=0
        overlap=(mask>0)&(weight>0)
        nvalid=0;shift90=0.;folded=0
        if np.count_nonzero(overlap)>1000 and not args.no_flow:
            ref=cv.cvtColor(np.uint8(np.clip(atlas,0,255)),cv.COLOR_BGR2GRAY)
            src=cv.cvtColor(warped,cv.COLOR_BGR2GRAY)
            # Equal boundary values prevent artificial black-edge motion.
            ref[~overlap]=src[~overlap]
            flow=dis.calc(ref,src,None)
            reverse=dis.calc(src,ref,None)
            back=cv.remap(reverse,xx+flow[:,:,0],yy+flow[:,:,1],cv.INTER_LINEAR)
            reliable=(cv.erode(overlap.astype(np.uint8),np.ones((13,13),np.uint8))>0)
            reliable&=(np.linalg.norm(flow+back,axis=2)<1.5)&(np.linalg.norm(flow,axis=2)<25)
            nvalid=int(reliable.sum())
            mass=cv.GaussianBlur(reliable.astype(np.float32),(0,0),12)
            smooth=cv.GaussianBlur(flow*reliable[:,:,None],(0,0),12)/np.maximum(mass[:,:,None],.05)
            smooth*=np.minimum(1,mass/.2)[:,:,None]
            # Reject folded or severely compressed local sampling maps.
            ux,uy=smooth[:,:,0],smooth[:,:,1]
            determinant=(1+np.gradient(ux,axis=1))*(1+np.gradient(uy,axis=0))-np.gradient(ux,axis=0)*np.gradient(uy,axis=1)
            invalid=(determinant<.5)|(determinant>2)
            folded=int(invalid.sum());smooth[invalid]=0
            shift90=float(np.percentile(np.linalg.norm(smooth[overlap],axis=1),90))
            warped=cv.remap(warped,xx+smooth[:,:,0],yy+smooth[:,:,1],cv.INTER_LINEAR)
            mask=cv.remap(mask,xx+smooth[:,:,0],yy+smooth[:,:,1],cv.INTER_NEAREST)
        distance=cv.distanceTransform(mask,cv.DIST_L2,3)
        incoming=np.minimum(distance/20,1)* (mask>0)
        # Average is a diagnostic control, not a sharpness-preserving compositor.
        old=np.minimum(weight,1)
        alpha=np.where(weight>0,incoming/(incoming+old+1e-6),incoming>0)
        if args.merge=='seam' and np.count_nonzero(overlap)>1000:
            size=(w//4,h//4)
            small=[cv.resize(im,size).astype(np.float32) for im in (atlas,warped)]
            sm=[cv.resize(m,size,interpolation=cv.INTER_NEAREST) for m in
                ((weight>0).astype(np.uint8)*255,mask)]
            selected=cv.detail_GraphCutSeamFinder('COST_COLOR_GRAD').find(small,[(0,0),(0,0)],sm)
            new=selected[1].get() if isinstance(selected[1],cv.UMat) else selected[1]
            ownership=cv.resize(new,(w,h),interpolation=cv.INTER_NEAREST)>0
            alpha=np.where(weight==0,mask>0,ownership&(mask>0)).astype(np.float32)
        atlas=atlas*(1-alpha[:,:,None])+warped*alpha[:,:,None]
        weight=np.maximum(weight,incoming)
        report.append(dict(name=name,overlapPixels=int(overlap.sum()),reliableFlowPixels=nvalid,
                           shiftP90=shift90,rejectedJacobianPixels=folded,seconds=time.perf_counter()-tick))
    elapsed=time.perf_counter()-start
    cv.imwrite(str(args.out/'panorama.jpg'),np.uint8(np.clip(atlas,0,255)))
    output=dict(seconds=elapsed,merge=args.merge,focalScale=args.focal_scale,sourceCount=total,frames=report,
                supportFraction=float(np.mean(weight>0)),quality='unverified')
    (args.out/'report.json').write_text(json.dumps(output,indent=2))
    print('seconds',elapsed,'support',output['supportFraction'])


if __name__=='__main__':main()
