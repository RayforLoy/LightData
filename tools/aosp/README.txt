Historical AOSP Mosaic desktop comparison (not current Google Camera)

Pinned upstream, Apache-2.0 headers retained in downloaded sources:
https://android.googlesource.com/platform/packages/apps/Camera2/+/8bddf8c/jni/
Download feature_mos and feature_stab archives using:
https://android.googlesource.com/platform/packages/apps/Camera2/+archive/8bddf8c/jni/feature_mos.tar.gz
https://android.googlesource.com/platform/packages/apps/Camera2/+archive/8bddf8c/jni/feature_stab.tar.gz
Observed SHA256 respectively:
ad7e55e89a0750d81ea81476719b31d271d52a346898bd13be6a8668e0f4b45d
ffe807ab3db7eb77c59b9eb345e6ad00253770c1c3de80ffee12e8e979549c69

Extract respectively to BASE/src and BASE/stab. The archive contains an
additional src level for mosaic. Do not modify algorithm sources.
Create BASE/include/android/log.h containing:
#pragma once
#define ANDROID_LOG_SILENT 0
#define ANDROID_LOG_INFO 1
#define ANDROID_LOG_ERROR 2
static inline int __android_log_print(int,const char*,const char*,...){return 0;}

Windows PowerShell, tested with MinGW GCC 6.3.0 (32-bit):
$base='.codex-attachments/aosp-benchmark'
gcc -O2 "-I$base/stab/src/dbreg" -c "$base/stab/src/dbreg/vp_motionmodel.c" -o "$base/motion.o"
$srcs = Get-ChildItem "$base/src/src/mosaic","$base/stab/db_vlvm","$base/stab/src/dbreg" -Filter *.cpp | ForEach-Object FullName
g++ -std=gnu++11 -O2 -fpermissive -DDBREG_EXPORTS -DLOG_TAG='"panotest"' "-I$base/include" "-I$base/src/src/mosaic" "-I$base/stab/src" "-I$base/stab/src/dbreg" "-I$base/stab/db_vlvm" tools/aosp/runner.cpp "$base/motion.o" @srcs -o "$base/aosp-panorama.exe"

Python 3.11, numpy, opencv-python required:
py -3.11 tools/benchmark_aosp_panorama.py BASE/aosp-panorama.exe INPUT.zip OUTPUT --edge 640

Modes: 0 FULL, 1 PAN, 2 CYLPAN, 3 HORZ. Upstream automatically uses thin
strips for FULL/PAN even though runner requests WIDE. Quarter-resolution
alignment disabled, still threshold zero; remaining algorithm defaults.
Each mode runs in a separate process with a 60-second timeout.
Native time includes registration and blending; wall time additionally
includes process/file IO, excludes Python JPEG decoding and RGB preparation.
No Android timing or peak-memory claim. At most 100 uniformly selected frames.
Neither retained frame count nor success return code is a quality verdict.
Private input and generated images must not be committed or uploaded.
