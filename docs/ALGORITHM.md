# Reading sequence

1. Focus using the registered pick-and-place component center. Wait for autofocus
   to settle; reject images before the focus timestamp plus 150 ms.
2. Refit the ferrite's four edges on the fresh image. Each edge stays within 5%
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
   Otherwise repeat fresh boundary localization. After three unsuccessful code
   boundary searches, advance and retry that unread component on the next circuit.

The Android app exports capture-scoped results. The local web tool polls these
results and draws the same strings beneath registered components. Values from a
different reference image are never reused. Display zoom does not change sensor
resolution or the image-to-panel mapping.

Defaults currently target the tested main camera: ISO 100, 112.5 ms exposure.
These values must be supported by the device. Hardware compatibility and lighting
still affect localization and reading; there is no universal success-rate claim.
