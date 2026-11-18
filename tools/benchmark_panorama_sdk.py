"""Run the complete OpenCV Stitcher on a private photo ZIP (opencv-python required).

No extraction or upload. Outputs stay in the explicitly selected local directory.
Status OK alone is not success: inspect participating inputs and the output image.
"""
import argparse
import json
from pathlib import Path
import time
import zipfile

import cv2
import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--edge", type=int, default=1200)
    args = parser.parse_args()
    if args.edge < 64:
        parser.error("--edge must be at least 64")
    args.output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.zip) as archive:
        names = sorted(n for n in archive.namelist() if n.lower().endswith((".jpg", ".jpeg")))
        images = []
        for name in names:
            image = cv2.imdecode(np.frombuffer(archive.read(name), np.uint8), cv2.IMREAD_COLOR)
            if image is None:
                raise ValueError(f"Cannot decode {name}")
            h, w = image.shape[:2]
            scale = min(1, args.edge / max(h, w))
            images.append(cv2.resize(image, (round(w * scale), round(h * scale))))
    if len(images) < 2:
        raise ValueError("Need at least two photos")
    cv2.setRNGSeed(0)
    stitcher = cv2.Stitcher_create(cv2.Stitcher_PANORAMA)
    start = time.perf_counter()
    status, panorama = stitcher.stitch(images)
    report = dict(opencv=cv2.__version__, input_count=len(images), input_edge=args.edge,
                  status=int(status), elapsed_seconds=time.perf_counter() - start)
    if status == cv2.Stitcher_OK:
        indices = [int(i) for i in stitcher.component()]
        report.update(participating_indices=indices, participating_names=[names[i] for i in indices],
                      all_inputs_used=len(indices) == len(images), output_shape=list(panorama.shape),
                      quality_verified=False)
        cv2.imwrite(str(args.output / "panorama.jpg"), panorama)
    (args.output / "report.json").write_text(json.dumps(report, indent=2), encoding="utf8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
