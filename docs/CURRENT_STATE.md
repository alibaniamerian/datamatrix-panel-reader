# Current implementation

Android sources, embedded Python analysis, tests, the English panel-registration
web tool, the ferrite-only production placement workbook and a synthetic example
placement workbook are the maintained project.
See [reading sequence](ALGORITHM.md) for active behavior.

Latest update (2026-11-09): web controls for ISO, shutter and camera light;
optional three-attempt ISO 50 retry; nominal 11-by-6 mm ferrite outlines followed
by post-focus edge refinement bounded to 5%; manual component retesting clears
only that component's prior result on both phone and web. Reference markers are
movable, resizable and rotatable 11:6 rectangles, including a live pointer preview.

Current verification: all 41 Python tests passed, as did the Java unread-cycle
test, JavaScript syntax check and rectangle interaction checks. The Android build
and phone installation succeeded during implementation of these changes.

Historical captures, experiments, consultant packages, build evidence and session
handoffs are preserved locally under `local_archive/` and excluded from Git.
The full original placement workbook remains in ignored `local_archive/private_placement/`.
The public `data/ferrite_positions.xlsx` contains only the 20 ferrite identifiers
and X/Y centers. Coordinates were checked against the original; the rebuilt
workbook contains no other component records or hidden sheets. The local copy in
ignored `inputs/` is also sanitized. Current phone/web session
data remains in ignored `outputs/panel_registration/`.

No license choice has been made on behalf of the project owner.

Cleanup verification: 38 Python tests passed in a source-only copy that has no
private inputs or local toolchains, using the synthetic placement workbook.
The Android build also succeeded. A local Git repository on branch `main` was
initialized. The retained current debug APK is
in ignored `local_archive/current_release/`. Rebuild it with the documented script.
