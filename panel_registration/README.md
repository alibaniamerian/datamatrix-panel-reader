# Local panel registration

See the [project README](../README.md) for setup and placement data.
Run `python panel_registration/server.py --serial YOUR_DEVICE_SERIAL`.
Open http://127.0.0.1:8765 and keep the phone app open.

The UI is English. Four anchors register the panel; exact read values are
polled from the app and persisted separately for each reference image.
