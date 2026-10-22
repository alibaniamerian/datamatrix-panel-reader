# DataMatrix Panel Reader

Android camera application and a local web tool for reading DataMatrix codes on
registered ferrite components. Supports native RAW analysis, YUV preview analysis,
manual component selection and automatic review of unread components.

## Project layout

- `android_capture/src/`: Android camera, overlay, focus and ZXing decoder.
- `android_capture/python/`: embedded image processing and localization.
- `android_capture/tests/`: geometry, sampling and decoder regression tests.
- `panel_registration/`: loopback-only web UI, placement reader and USB bridge.
- `data/ferrite_positions.xlsx`: actual positions of the 20 ferrites only; no other components.
- `examples/panel.example.xlsx`: synthetic 4-by-5 placement example, not a real PCB.
- `docs/ALGORITHM.md`: the maintained reading sequence and limits.

Local archives, original captures, private placement files, installed tools,
credentials and generated results are excluded from Git. Historical work is
preserved in `local_archive/`, not part of the publication.

## Run the local web tool (Windows)

Install Python 3.12+, Android platform-tools, and the phone's USB driver if needed.
Enable USB debugging and authorize this computer on the phone.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
adb devices
.\.venv\Scripts\python.exe panel_registration/server.py --serial YOUR_DEVICE_SERIAL
```

Open http://127.0.0.1:8765 and keep the Android app foreground. Take a reference
photo, mark the four requested centers, review all component positions and send
the alignment to the phone. Successfully decoded strings appear beneath the
components on both phone and web. Results are scoped to the reference photo.
The server binds only to loopback and does not use a cloud service.

`adb` is found on PATH, or via `ADB_PATH`. The optional serial can also come from
`ANDROID_SERIAL`; with one connected device it may be omitted. Existing local
`tools/android/platform-tools/adb.exe` remains a fallback.

### Your own placement data

The default workbook, `data/ferrite_positions.xlsx`, contains only the 20 ferrite
designators and their X/Y centers in millimeters. It was rebuilt from the private
placement file without other components, extra columns, hidden sheets or source
metadata. The full original is retained only in ignored local archives.
`examples/panel.example.xlsx` is a synthetic fallback. Other layouts require updating the placement path, component
selection and four anchor names in `panel_registration/panel.py`.

Required columns: `Designator`, `Center-X(mm)`, `Center-Y(mm)`. Optional columns:
`Rotation`, `Layer`, `Footprint`. Current component naming is `CORE1`, `CORE1_1`
through `CORE1_19`; anchors are CORE1, CORE1_3, CORE1_19, CORE1_16.

## Build the Android app

Prerequisites: JDK 17, Android SDK platform 36, Gradle 8.11.1, and Python 3.10 for
Chaquopy's build-time interpreter. Python 3.10 is separate from the host web venv.
Set `JAVA_HOME`, `ANDROID_HOME`, and `PYTHON310` to your installed paths.

```powershell
$env:PYTHON310 = 'C:\path\to\Python310\python.exe'
.\android_capture\build-live.ps1
adb -s YOUR_DEVICE_SERIAL install -r android_capture/build/outputs/apk/debug/DataMatrixCapture-debug.apk
```

The build script accepts `-Sdk`, `-Jdk`, `-Gradle`. Local-only overrides can be
stored in ignored `android_capture/.local/build-config.json`, with `Sdk` and `Jdk`
properties. A standard debug signing key is generated on a fresh checkout. An
existing ignored `.local/debug.keystore` is retained for compatible local updates.
Never commit signing keys or machine-specific `local.properties`.

## Tests

```powershell
.\scripts\run-tests.ps1
```

Python tests use synthetic arrays and the placement workbook. Native Java test
sources are in `android_capture/tests/`; the decoder test requires ZXing and
org.json on the Java classpath. No phone is needed for the Python tests.

## Publication

This folder is prepared for source publication. `.gitignore` excludes local data
and toolchains; the production workbook contains only approved ferrite positions,
and the example workbook is synthetic. A software license has not yet been
selected by the owner. See `docs/CURRENT_STATE.md` for the local archive policy.
