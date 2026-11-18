"""Run the historical AOSP Mosaic executable on private photo/diagnostic ZIPs.

Build instructions: tools/aosp/README.txt. Outputs remain local. Successful
native return codes and retained frames are NOT a visual-quality verdict.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
import cv2 as cv
import numpy as np
from panorama_offline_matrix import read_frames


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('executable', type=Path)
    parser.add_argument('zip', type=Path)
    parser.add_argument('out', type=Path)
    parser.add_argument('--edge', type=int, default=640)
    parser.add_argument('--modes', default='0,1,2,3')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    images, _, names, total = read_frames(args.zip, args.edge, 100)
    height, width = images[0].shape[:2]
    if any(im.shape != images[0].shape for im in images):
        raise ValueError('AOSP requires identical frame dimensions')
    paths = []
    for index, im in enumerate(images):
        path = args.out / f'{index:03d}.rgb'
        cv.cvtColor(im, cv.COLOR_BGR2RGB).tofile(str(path))
        paths.append(str(path.resolve()))
    for mode in map(int, args.modes.split(',')):
        raw = args.out / f'mode{mode}.bgr'
        start = time.perf_counter()
        result = subprocess.run([str(args.executable.resolve()), str(width),
                                 str(height), str(mode), str(raw.resolve()),
                                 *paths], capture_output=True, text=True, timeout=60)
        elapsed = time.perf_counter() - start
        (args.out / f'mode{mode}.log').write_text(result.stdout + result.stderr)
        report = dict(mode=mode, exitCode=result.returncode, wallSeconds=elapsed,
                      sourceCount=total, selectedNames=names,
                      quality='UNVERIFIED; inspect geometry, coverage and seams')
        lines = result.stdout.splitlines()
        frames = [line for line in lines if line.startswith('FRAME ')]
        report['frameDecisions'] = frames
        final = next((line.split() for line in lines if line.startswith('RESULT ')), None)
        if final:
            _, status, w, h, seconds = final
            report.update(status=int(status), width=int(w), height=int(h),
                          nativeSeconds=float(seconds))
            if result.returncode == 0 and int(status) == 1:
                output = np.fromfile(str(raw), np.uint8).reshape(int(h), int(w), 3)
                cv.imwrite(str(args.out / f'mode{mode}.jpg'), output)
        (args.out / f'mode{mode}.json').write_text(json.dumps(report, indent=2))
        print(json.dumps({k: v for k, v in report.items()
                          if k not in ('frameDecisions', 'selectedNames')}), flush=True)


if __name__ == '__main__':
    main()
