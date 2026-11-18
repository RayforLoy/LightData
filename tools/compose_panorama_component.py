"""Research baseline: local homography graph + OpenCV seams/multiband.

No pose fallback, no claim of global metric correctness. Only the largest
verified component is composed; explicitly report all excluded source frames.
"""
import argparse
import heapq
import json
import time
from pathlib import Path
import cv2 as cv
import numpy as np
from panorama_offline_matrix import read_frames


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('zip',type=Path)
    parser.add_argument('audit',type=Path)
    parser.add_argument('out',type=Path)
    parser.add_argument('--model',choices=['homography','similarity'],default='homography')
    args=parser.parse_args()
    start=time.perf_counter();cv.setNumThreads(4)
    images,_,names,total=read_frames(args.zip,720,61)
    data=json.loads(args.audit.read_text())
    graph={name:[] for name in names}
    for p in data['pairs']:
        a,b=p['i'],p['j']
        error=p['similarityError'] if args.model=='similarity' else p['localHomography']
        if error is None or error['median']>=3 or p['supportFraction']<.05:continue
        if args.model=='homography' and p['crossesHorizon']:continue
        h=np.array(p[args.model])
        ih=np.linalg.inv(h)
        height,width=images[names.index(b)].shape[:2]
        depths=np.array([[0,0,1],[width,0,1],[width,height,1],[0,height,1]])@ih[2]
        if depths.min()*depths.max()<=0:continue
        cost=1+error['median']
        graph[a].append((b,h,cost));graph[b].append((a,np.linalg.inv(h),cost))
    remaining=set(names);components=[]
    while remaining:
        seed=min(remaining);remaining.remove(seed);group={seed};queue=[seed]
        while queue:
            for b,_,_ in graph[queue.pop()]:
                if b in remaining:remaining.remove(b);group.add(b);queue.append(b)
        components.append(group)
    component=sorted(max(components,key=len),key=lambda n:names.index(n))
    root=component[len(component)//2]
    queue=[(0,root)];costs={root:0};transforms={root:np.eye(3)}
    while queue:
        cost,a=heapq.heappop(queue)
        if cost!=costs[a]:continue
        for b,h,edge in graph[a]:
            if cost+edge<costs.get(b,float('inf')):
                costs[b]=cost+edge
                transforms[b]=transforms[a]@np.linalg.inv(h)
                heapq.heappush(queue,(cost+edge,b))
    corners=[];warped=[];masks=[]
    for name in component:
        im=images[names.index(name)];h,w=im.shape[:2];transform=transforms[name]
        pts=np.float32([[0,0],[w,0],[w,h],[0,h]])
        zs=np.column_stack([pts,np.ones(4)])@transform[2]
        if np.min(zs)*np.max(zs)<=0:raise ValueError(f'Warp crosses horizon for {name}; depths {zs}')
        pts=cv.perspectiveTransform(pts[None],transform)[0]
        lo=np.floor(pts.min(axis=0)).astype(int);hi=np.ceil(pts.max(axis=0)).astype(int)
        size=hi-lo
        if np.any(size<=0) or np.prod(size)>4000000:raise ValueError('Unbounded warp')
        shift=np.array([[1,0,-lo[0]],[0,1,-lo[1]],[0,0,1.]])
        warped.append(cv.warpPerspective(im,shift@transform,tuple(size)))
        masks.append(cv.warpPerspective(np.full((h,w),255,np.uint8),shift@transform,
                                       tuple(size),flags=cv.INTER_NEAREST))
        corners.append(tuple(map(int,lo)))
    roi=cv.detail.resultRoi(corners=corners,sizes=[(im.shape[1],im.shape[0]) for im in warped])
    if roi[2]*roi[3]>8000000:raise ValueError('Unbounded mosaic')
    small=[cv.resize(im,None,fx=.25,fy=.25) for im in warped]
    small_masks=[cv.resize(m,(im.shape[1],im.shape[0]),interpolation=cv.INTER_NEAREST)
                 for m,im in zip(masks,small)]
    small_corners=[(round(x/4),round(y/4)) for x,y in corners]
    # Keep raw alignment preview for distinguishing geometry from blending.
    args.out.mkdir(parents=True,exist_ok=True)
    for compensate in (False,True):
        current=[im.copy() for im in warped]
        if compensate:
            exposure=cv.detail.ExposureCompensator_createDefault(cv.detail.ExposureCompensator_GAIN_BLOCKS)
            exposure.feed(small_corners,small,small_masks)
            for i,im in enumerate(current):exposure.apply(i,corners[i],im,masks[i])
        seam=cv.detail_GraphCutSeamFinder('COST_COLOR_GRAD')
        seam_images=[cv.resize(im,None,fx=.25,fy=.25).astype(np.float32) for im in current]
        selected=seam.find(seam_images,small_corners,[m.copy() for m in small_masks])
        blender=cv.detail_MultiBandBlender();blender.setNumBands(4);blender.prepare(roi)
        for im,m,original,corner in zip(current,selected,masks,corners):
            m=m.get() if isinstance(m,cv.UMat) else m
            m=cv.resize(cv.dilate(m,None),(im.shape[1],im.shape[0]),interpolation=cv.INTER_NEAREST)
            blender.feed(im.astype(np.int16),m&original,corner)
        result,support=blender.blend(None,None)
        result=np.clip(result,0,255).astype(np.uint8)
        label='compensated' if compensate else 'uncompensated'
        cv.imwrite(str(args.out/(label+'.jpg')),result)
        factor=min(1,1000/max(result.shape[:2]))
        cv.imwrite(str(args.out/(label+'-preview.jpg')),cv.resize(result,None,fx=factor,fy=factor))
    report=dict(model=args.model,root=root,participating=component,sourceCount=total,
                excluded=[n for n in names if n not in component],roi=roi,
                seconds=time.perf_counter()-start,
                limitations='Only one component; tree composition, no global optimization; gains are appearance only, not measurement.')
    (args.out/'report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report))


if __name__=='__main__':main()
