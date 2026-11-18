"""Run an external APAP reference implementation without vendoring its code.

Tested upstream: https://github.com/EadCat/APAP-Image-Stitching
Commit: 2ad0805b4fb921e72f1226bc38da1a7aae745dd9
This third-party implementation is not an Android SDK. Its failure path can
save a side-by-side collage and exit zero: classify that as failure, not stitch.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
import zipfile


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('repo',type=Path)
    p.add_argument('zip',type=Path)
    p.add_argument('out',type=Path)
    p.add_argument('--frames',required=True,help='Exact ZIP member names, comma separated')
    p.add_argument('--mesh',type=int,default=32)
    p.add_argument('--threshold',type=float,default=3)
    args=p.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    inputs=args.out/'inputs';inputs.mkdir(exist_ok=True)
    selected=args.frames.split(',');paths=[]
    with zipfile.ZipFile(args.zip) as archive:
        for i,name in enumerate(selected):
            dest=inputs/f'{i:03d}.jpg';dest.write_bytes(archive.read(name))
            paths.append(str(dest.resolve()))
    revision=subprocess.check_output(['git','-C',str(args.repo),'rev-parse','HEAD'],text=True).strip()
    result_dir=args.out/'result'
    cmd=[sys.executable,'-c',
         "import random,numpy as np,runpy;random.seed(0);np.random.seed(0);runpy.run_path('main.py',run_name='__main__')",
         '--imgs',*paths,'--resize','480','640','--mesh_size',str(args.mesh),
         '--ransac_thres',str(args.threshold),'--ransac_sample','4',
         '--multiband','True','--unit_time','True','--verbose','True',
         '--saveroot',str(result_dir.resolve())]
    start=time.perf_counter()
    try:
        run=subprocess.run(cmd,cwd=args.repo,capture_output=True,text=True,timeout=60)
        log=run.stdout+run.stderr
        status='failed' if run.returncode or 'Homography Estimation Failed' in log else 'requires_visual_review'
    except subprocess.TimeoutExpired:
        log='Timed out after 60 seconds';status='timeout'
    report=dict(upstream='https://github.com/EadCat/APAP-Image-Stitching',revision=revision,
                selected=selected,mesh=args.mesh,ransacThreshold=args.threshold,
                seconds=time.perf_counter()-start,status=status,
                limitations='Third-party research code; images resized to 480x640; no Android timing; inspect stretching as well as seams.')
    (args.out/'run.log').write_text(log,encoding='utf8')
    (args.out/'report.json').write_text(json.dumps(report,indent=2),encoding='utf8')
    print(json.dumps(report))


if __name__=='__main__':main()
