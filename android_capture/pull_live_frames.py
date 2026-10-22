"""Pull frames saved by the app's "Save" toggle, plus the matching analysis log, into a new folder.

Usage:  .venv\\Scripts\\python.exe android_capture\\pull_live_frames.py <output folder> [--serial DEVICE_SERIAL]
Each frame (live_raw_<ts>.dng or live_yuv_<ts>_Y.png + .json) is verified by SHA256 after transfer.
logcat (LiveBoundary / FrameSave / CodeFocus) is saved as live_log.txt; the app logs image_timestamp_ns
for every analysis, so frames and results join on that timestamp. Files on the phone are not deleted.
"""
import argparse
import os
import shutil
import hashlib
import json
import subprocess
from pathlib import Path

ADB = str(Path(__file__).resolve().parent.parent / 'tools' / 'android' / 'platform-tools' / 'adb.exe')
REMOTE = '/sdcard/Android/data/org.datamatrix.rawcapture/files/live_frames'


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('output', type=Path)
    ap.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    a = ap.parse_args()
    adb = [os.environ.get('ADB_PATH') or shutil.which('adb') or ADB] + (['-s', a.serial] if a.serial else [])
    a.output.mkdir(parents=True, exist_ok=True)
    names = subprocess.run(adb + ['shell', f'ls {REMOTE} 2>/dev/null'], capture_output=True, text=True).stdout.split()
    pulled, skipped = [], 0
    for name in sorted(names):
        local = a.output / name
        expected = subprocess.run(adb + ['shell', 'sha256sum', f'{REMOTE}/{name}'], capture_output=True, text=True).stdout.split()[0]
        if local.exists() and hashlib.sha256(local.read_bytes()).hexdigest() == expected:
            skipped += 1
            continue
        subprocess.run(adb + ['pull', f'{REMOTE}/{name}', str(local)], check=True, capture_output=True)
        if hashlib.sha256(local.read_bytes()).hexdigest() != expected:
            raise SystemExit(f'checksum mismatch: {name}')
        pulled.append(dict(file=name, sha256=expected))
    log = subprocess.run(adb + ['logcat', '-d', '-v', 'time', '-s', 'LiveBoundary:*', 'FrameSave:*', 'CodeFocus:*', 'Filters:*'],
                         capture_output=True, text=True, encoding='utf-8', errors='replace').stdout
    (a.output / 'live_log.txt').write_text(log, encoding='utf-8')
    (a.output / 'pull_manifest.json').write_text(json.dumps(dict(remote=REMOTE, pulled=pulled, already_present=skipped), indent=1), encoding='utf-8')
    print(f'pulled {len(pulled)} files, {skipped} already present, log lines {log.count(chr(10))}')


if __name__ == '__main__':
    main()
