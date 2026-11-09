# Reading sequence

1. Focus using the registered pick-and-place component center. Wait for autofocus
   to settle; reject images before the focus timestamp plus 150 ms.
2. The overview projects an exact 11-by-6 mm rectangle from each placement center
   through the four-anchor homography. No overview-image edge fitting is used.
   Refit the ferrite's four edges only on the fresh post-focus image, always
   starting from that immutable nominal rectangle. Each edge stays within 5%
   of the initial body's perpendicular extent, including both endpoints. The
   anchor never moves to the preceding frame's result.
3. Model the ferrite surface with a robust quadratic background. Search physical
   code windows from the center outward; use signed, absolute-deviation and
   illumination planes to independently refine the code edges. The final code
   quadrilateral is not forced square.
4. Sample candidate ECC200 grids and use ZXing. Try both handednesses and four
   rotations, since panel coordinates can reflect the observed matrix. Never
   insert a known payload or alter individual bits to force a match.
5. On success, retain the exact text under its component and advance immediately.
   Otherwise repeat fresh boundary localization. After three unsuccessful attempts
   at ISO 100 (including body-localization failures), refocus the same component
   at ISO 50 and allow three fresh attempts. Then advance and retry that unread
   component on the next circuit, starting again at ISO 100. Devices without ISO
   50 support advance after the ISO 100 attempts.

The Android app exports capture-scoped results. The local web tool polls these
results and draws the same strings beneath registered components. Values from a
different reference image are never reused. Display zoom does not change sensor
resolution or the image-to-panel mapping.

Defaults currently target the tested main camera: ISO 100, 112.5 ms exposure.
These values must be supported by the device. Hardware compatibility and lighting
still affect localization and reading; there is no universal success-rate claim.
