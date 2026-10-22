"""Geometry and sampling port: v1.2 stages A-F helpers. No decoder or RS test."""

import cv2

import numpy as np

DEFAULTS = dict(part_scale=4, part_fill=0.3, part_fill_strict=0.85, part_contrast=0.5, part_inset=12, tophat_size=121,
                tophat_thresholds=(0.5, 0.3), part_openings=(1, 11, 21), max_part_candidates=8, planes=('signed', 'absdev'), part_min_side=80, min_symbol_side=30,
                refine_stats=('mean', 70), refine_min_conformance=0.75, grid_refine_max=4.0, dog=(1.5, 4.0), energy_sigma=8.0,
                region_floor=3.0, region_frac=0.3, bg_sigma=15.0, dark_sigma=0.8, wall_angle=3.0,
                wall_search=(-10.0, 8.0), outer_frac=0.6, timing_angle=0.75, timing_offset=1.5, cell_inset=0.25,
                max_modules=40)

ORDER = ('top', 'right', 'bottom', 'left')

OPPOSITE = dict(top='bottom', bottom='top', left='right', right='left')

def bilinear(a, x, y):
    # Physical wall searches at native RAW scale can exceed OpenCV's 32767
    # destination-column limit. Chunk samples without changing their positions.
    if np.asarray(x).size>30000:
        xx=np.asarray(x).ravel();yy=np.asarray(y).ravel()
        return np.concatenate([bilinear(a,xx[i:i+30000],yy[i:i+30000]) for i in range(0,len(xx),30000)])
    return cv2.remap(a, np.float32(x).reshape(1, -1), np.float32(y).reshape(1, -1), cv2.INTER_LINEAR,
                     borderMode=cv2.BORDER_REPLICATE)[0]

def rotate(v, deg):
    r = np.radians(deg)
    return np.array([v[0] * np.cos(r) - v[1] * np.sin(r), v[0] * np.sin(r) + v[1] * np.cos(r)])

def part_masks(small, p):
    """Dark-part masks from two independent rules (v1.2):
    global Otsu (part darker than the whole scene; v1.1 rule) and black top-hat (part darker than its
    own bright surround by > threshold; robust when a dark mat pulls the global threshold down).
    Each mask is also opened at increasing sizes to cut thin necks (e.g. a touching finger)."""
    u8 = cv2.normalize(small, None, 0, 255, cv2.NORM_MINMAX).astype(np.uint8)
    t, _ = cv2.threshold(u8, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (p['tophat_size'],) * 2)
    tophat = cv2.morphologyEx(small, cv2.MORPH_CLOSE, k) - small
    bases = [('otsu', (u8 < t).astype(np.uint8))]
    bases += [(f'tophat{thr}', (tophat > thr).astype(np.uint8)) for thr in p['tophat_thresholds']]
    for name, m in bases:
        for size in p['part_openings']:
            yield f'{name}_open{size}', m if size <= 1 else cv2.morphologyEx(
                m, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (size, size)))

def find_parts(gray, p):
    f = p['part_scale']
    small = np.log(cv2.resize(gray, None, fx=1 / f, fy=1 / f, interpolation=cv2.INTER_AREA) + 64)
    H, W = small.shape
    parts = []
    for source, mask in part_masks(small, p):
        n, lab, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=4)
        for i in range(1, n):
            x, y, w, h, area = stats[i]
            if x == 0 or y == 0 or x + w >= W or y + h >= H or area < 100 or area > 0.05 * H * W:
                continue
            blob = (lab[y:y + h, x:x + w] == i).astype(np.uint8)
            cnt = max(cv2.findContours(blob, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)[0], key=cv2.contourArea) + [x, y]
            (cx, cy), (rw, rh), ang = cv2.minAreaRect(cnt.astype(np.float32))
            fill = area / max(rw * rh, 1)
            if fill <= p['part_fill'] or min(rw, rh) * f < p['part_min_side']:
                continue
            full = np.zeros((H, W), np.uint8)
            full[y:y + h, x:x + w] = blob
            ring = cv2.dilate(full, np.ones((9, 9), np.uint8)) - cv2.dilate(full, np.ones((3, 3), np.uint8))
            contrast = float(np.median(small[ring > 0]) - np.median(small[full > 0]))
            if contrast <= p['part_contrast']:
                continue
            box = [int(x * f), int(y * f), int((x + w) * f), int((y + h) * f)]
            dup = next((q for q in parts if iou(q['bbox'], box) > 0.6), None)
            if dup:
                dup['sources'].append(source)
                continue
            parts.append(dict(rect=[[cx * f, cy * f], [rw * f, rh * f], float(ang)], fill=float(fill),
                              surround_log_contrast=contrast, bbox=box, sources=[source],
                              _blob=blob, _blob_origin=(int(x), int(y))))
    # rectangular, high-contrast candidates first; symbol evidence decides later
    return sorted(parts, key=lambda q: -q['fill'] * q['surround_log_contrast'])[:p['max_part_candidates']]

def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)

def analyse(gray, part, p):
    x0, y0, x1, y1 = part['bbox']
    x0, y0 = max(x0 - 30, 0), max(y0 - 30, 0)
    x1, y1 = min(x1 + 30, gray.shape[1]), min(y1 + 30, gray.shape[0])
    c = np.log(gray[y0:y1, x0:x1] + 64)
    # component mask (not its rectangle): copes with a part merged with a finger/shadow (v1.2)
    f = p['part_scale']
    bx, by = part['_blob_origin']
    blob = cv2.resize(part['_blob'], None, fx=f, fy=f, interpolation=cv2.INTER_NEAREST)
    m = np.zeros(c.shape, np.uint8)
    ox, oy = bx * f - x0, by * f - y0
    sy, sx = slice(max(oy, 0), min(oy + blob.shape[0], c.shape[0])), slice(max(ox, 0), min(ox + blob.shape[1], c.shape[1]))
    m[sy, sx] = blob[sy.start - oy:sy.stop - oy, sx.start - ox:sx.stop - ox]
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * f + 1,) * 2))
    k = 2 * p['part_inset'] + 1
    m = cv2.erode(m, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    if m.sum() < 400:
        raise LookupError('part too small after inset')
    mf, M = m.astype(np.float32), m > 0
    s1, s2 = p['dog']
    band = cv2.GaussianBlur(c, (0, 0), s1) - cv2.GaussianBlur(c, (0, 0), s2)
    norm = np.maximum(cv2.GaussianBlur(mf, (0, 0), p['energy_sigma']), 1e-3)
    energy = cv2.GaussianBlur(band * band * mf, (0, 0), p['energy_sigma']) / norm * mf
    med = np.median(energy[M])
    z = (energy - med) / max(np.median(np.abs(energy[M] - med)) * 1.4826, 1e-12)
    peak = float(np.percentile(z[M], 99.5))
    thr = max(p['region_floor'], p['region_frac'] * peak)
    n, lab, st, _ = cv2.connectedComponentsWithStats((z > thr).astype(np.uint8))
    if n < 2:
        raise LookupError('no textured region inside the part')
    region = lab == 1 + int(np.argmax(st[1:, 4]))
    sm = cv2.GaussianBlur(c, (0, 0), 1.0)
    gx, gy = cv2.Sobel(sm, cv2.CV_32F, 1, 0), cv2.Sobel(sm, cv2.CV_32F, 0, 1)
    vec = ((gx * gx + gy * gy) * np.exp(4j * np.arctan2(gy, gx)))[region]
    theta = float(np.angle(vec.sum()) / 4)
    coherence = float(abs(vec.sum()) / np.sum(np.abs(vec)))
    bg = cv2.GaussianBlur(c * mf, (0, 0), p['bg_sigma']) / np.maximum(cv2.GaussianBlur(mf, (0, 0), p['bg_sigma']), 1e-3)
    dark = (bg - cv2.GaussianBlur(c, (0, 0), p['dark_sigma'])) * mf
    polarity = 1.0 if dark[region].mean() > np.median(dark[M & ~region]) else -1.0
    U = np.array([np.cos(theta), np.sin(theta)])
    V = np.array([-np.sin(theta), np.cos(theta)])
    ys, xs = np.nonzero(region)
    P = np.column_stack((xs, ys)).astype(float)
    pu, pv = P @ U, P @ V
    ext = dict(u0=np.percentile(pu, 1), u1=np.percentile(pu, 99), v0=np.percentile(pv, 1), v1=np.percentile(pv, 99))
    # v1.2: engraving evidence = |deviation| from a quadratic surface model fitted to part pixels away from
    # the symbol. Specular glints inside engraved modules (laser raster lines) are brighter than the surface
    # and cancel darkness in the signed plane; in |deviation| both count as engraving.
    surf = M & ~cv2.dilate(region.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (31, 31))).astype(bool)
    if surf.sum() < 200:
        surf = M & ~region
    sy, sx = np.nonzero(surf)
    step = max(1, len(sx) // 20000)
    sx, sy = sx[::step], sy[::step]
    hh, ww = c.shape
    def design(x, y):
        x, y = x / ww - 0.5, y / hh - 0.5
        return np.column_stack((np.ones_like(x), x, y, x * x, x * y, y * y))
    coef = np.linalg.lstsq(design(sx.astype(float), sy.astype(float)), c[sy, sx], rcond=None)[0]
    yy, xx = np.mgrid[0:hh, 0:ww]
    surface = (design(xx.ravel().astype(float), yy.ravel().astype(float)) @ coef).reshape(hh, ww).astype(np.float32)
    dev = np.abs(cv2.GaussianBlur(c, (0, 0), p['dark_sigma']) - surface)
    absdev = cv2.GaussianBlur(dev, (0, 0), 1.0)
    absdev = (absdev - np.median(absdev[surf])) * mf
    # dev-team plane (2026-10-19 report, path 3): Gaussian(gray, 8) - gray on the linear crop, own dark_sigma
    lin = gray[y0:y1, x0:x1].astype(np.float32)
    illum8 = (cv2.GaussianBlur(lin, (0, 0), 8) - cv2.GaussianBlur(lin, (0, 0), p['dark_sigma'])) * mf * polarity
    return dict(origin=(x0, y0), dark=dark * polarity, planes=dict(signed=dark * polarity, absdev=absdev, illum8=illum8),
                mask=M, region=region, theta=theta, coherence=coherence,
                polarity=polarity, U=U, V=V, ext=ext, energy_peak_z=peak, region_threshold_z=thr,
                region_pixels=int(region.sum()))

def edge_contrast(d, base, n):
    inside = np.mean([bilinear(d, *(base - n * k).T) for k in (0.75, 1.5, 2.25)], axis=0)
    outside = np.mean([bilinear(d, *(base + n * k).T) for k in (0.75, 1.5, 2.25)], axis=0)
    return inside - outside

DEPTHS = np.array([0.75, 1.5, 2.25])

def fit_wall(d, normal, tangent, off0, t0, t1, p):
    mid = normal * off0 + tangent * (t0 + t1) / 2
    ts = np.linspace(-0.8, 0.8, 60) * (t1 - t0) / 2
    shifts = np.arange(p['wall_search'][0], p['wall_search'][1] + 1e-9, 0.25)
    # signed distance along the normal for (inside depths, outside depths); one remap per angle
    k = np.concatenate([-DEPTHS, DEPTHS])
    trials = []
    for da in np.arange(-p['wall_angle'], p['wall_angle'] + 1e-9, 0.25):
        n = rotate(normal, da)
        tg = np.array([-n[1], n[0]])
        pts = (mid[None, None, None] + n * (shifts[:, None, None, None] + k[None, :, None, None])
               + tg * ts[None, None, :, None])  # shifts x depths x ts x 2
        v = bilinear(d, pts[..., 0].ravel(), pts[..., 1].ravel()).reshape(len(shifts), len(k), len(ts))
        contrast = v[:, :3].mean(axis=(1, 2)) - v[:, 3:].mean(axis=(1, 2))
        for s_, c_ in zip(shifts, contrast):
            trials.append((float(c_), float(s_), float(da), n, float(n @ (mid + n * s_))))
    best = max(t[0] for t in trials)
    ok = [t for t in trials if t[0] >= p['outer_frac'] * best]
    outer = max(t[1] for t in ok)
    pick = max((t for t in ok if t[1] >= outer - 1.0), key=lambda t: t[0])
    return dict(contrast=pick[0], best_contrast=best, angle_offset_deg=pick[2], n=pick[3], off=pick[4])

def intersect(a, b):
    return np.linalg.solve(np.array([a['n'], b['n']]), np.array([a['off'], b['off']]))

def quad_of(w):
    return np.array([intersect(w['top'], w['left']), intersect(w['top'], w['right']),
                     intersect(w['bottom'], w['right']), intersect(w['bottom'], w['left'])])

def first_walls(an, p):
    U, V, e, d = an['U'], an['V'], an['ext'], an['dark']
    return dict(left=fit_wall(d, -U, V, -e['u0'], e['v0'], e['v1'], p),
                right=fit_wall(d, U, V, e['u1'], e['v0'], e['v1'], p),
                top=fit_wall(d, -V, U, -e['v0'], e['u0'], e['u1'], p),
                bottom=fit_wall(d, V, U, e['v1'], e['u0'], e['u1'], p))

def along(w, ts):
    n = w['n']
    tg = np.array([-n[1], n[0]])
    return n[None] * w['off'] + tg[None] * ts[:, None], tg

def line_end(d, wall, corner, far_t, mid):
    _, tg = along(wall, np.zeros(1))
    tc = corner @ tg
    sgn = np.sign(far_t - tc)
    ts = tc + sgn * np.arange(0, abs(far_t - tc) + 15, 0.1)
    base, _ = along(wall, ts)
    prof = np.mean([bilinear(d, *(base - wall['n'] * k).T) for k in (1.0, 2.0, 3.0)], axis=0)
    prof = np.convolve(prof, np.ones(9) / 9, mode='same')
    idx = np.nonzero(prof > mid)[0]
    j = int(idx.max()) if len(idx) else len(ts) - 1
    tend = ts[j]
    if j + 1 < len(ts):
        f = (prof[j] - mid) / max(prof[j] - prof[j + 1], 1e-12)
        tend += sgn * 0.1 * min(max(f, 0.0), 1.0)
    return along(wall, np.array([tend]))[0][0]

def timing_wall(d, through, normal, tangent, length, p):
    best = None
    ts = np.linspace(0.05, 0.95, 80) * length
    for da in np.arange(-p['timing_angle'], p['timing_angle'] + 1e-9, 0.25):
        n = rotate(normal, da)
        tg = np.array([-n[1], n[0]])
        if tg @ tangent < 0:
            tg = -tg
        for s in np.arange(-p['timing_offset'], p['timing_offset'] + 1e-9, 0.25):
            base = through[None] + n[None] * s + tg[None] * ts[:, None]
            c = np.sort(edge_contrast(d, base, n))[len(ts) // 2:].mean()  # stronger half: alternating modules
            if best is None or c > best[0]:
                best = (float(c), float(da), n, float(n @ (through + n * s)))
    return dict(contrast=best[0], angle_offset_deg=best[1], n=best[2], off=best[3])

def l_walls(an, walls, p):
    d, M = an['dark'], an['mask']
    surf = float(np.median(d[M & ~an['region']]))
    pairs = [(ORDER[i], ORDER[(i + 1) % 4]) for i in range(4)]
    s1, s2 = max(pairs, key=lambda q: walls[q[0]]['best_contrast'] + walls[q[1]]['best_contrast'])
    corner = intersect(walls[s1], walls[s2])
    q = quad_of(walls)
    inside_vals = []
    for k in (s1, s2):
        _, tg = along(walls[k], np.zeros(1))
        ts = np.linspace(*sorted([(q @ tg).min(), (q @ tg).max()]), 200)
        base, _ = along(walls[k], ts)
        inside_vals.append(np.mean([bilinear(d, *(base - walls[k]['n'] * j).T) for j in (1.0, 2.0, 3.0)], axis=0))
    mid = (float(np.percentile(np.concatenate(inside_vals), 90)) + surf) / 2
    ends = {}
    for k in (s1, s2):
        _, tg = along(walls[k], np.zeros(1))
        proj = q @ tg
        far = proj.max() if abs(proj.max() - corner @ tg) > abs(proj.min() - corner @ tg) else proj.min()
        ends[k] = line_end(d, walls[k], corner, far, mid)
    out = dict(walls)
    for solid, other in ((s1, s2), (s2, s1)):
        tangent = ends[solid] - corner
        length = float(np.linalg.norm(tangent))
        out[OPPOSITE[solid]] = timing_wall(d, ends[other], -walls[solid]['n'], tangent / length, length, p)
    return out, dict(solid_sides=[s1, s2], l_corner=corner, l_ends=ends, half_level=mid, surface_level=surf)


def sample_board(d, quad, rows, cols, inset, k=3, stat='mean'):
    H = cv2.getPerspectiveTransform(np.float32([[0, 0], [1, 0], [1, 1], [0, 1]]), np.float32(quad))
    t = np.linspace(inset, 1 - inset, k)
    cu = (np.arange(cols)[:, None] + t[None]) / cols
    cv = (np.arange(rows)[:, None] + t[None]) / rows
    uu = np.repeat(np.repeat(cu[None, :, :, None], rows, 0), k, 3)
    vv = np.repeat(np.repeat(cv[:, None, None, :], cols, 1), k, 2)
    xy = cv2.perspectiveTransform(np.stack([uu, vv], -1).reshape(1, -1, 2).astype(np.float32), H)[0]
    s = bilinear(d, xy[:, 0], xy[:, 1]).reshape(rows, cols, k * k)
    return s.mean(-1) if stat == 'mean' else np.percentile(s, stat, axis=-1)

def two_means(v):
    t = float(np.median(v))
    for _ in range(100):
        lo, hi = v[v <= t], v[v > t]
        if not len(lo) or not len(hi):
            break
        t2 = (lo.mean() + hi.mean()) / 2
        if abs(t2 - t) < 1e-12:
            break
        t = t2
    return t

def finder_conformance(dark):
    best = (-1.0, 0)
    for rot in range(4):
        b = np.rot90(dark, rot)
        R, C = b.shape
        top = np.arange(C) % 2 == 0
        right = (R - 1 - np.arange(R)) % 2 == 0
        score = np.concatenate([b[:, 0] == 1, b[-1] == 1, b[0] == top, b[:, -1] == right]).mean()
        if score > best[0]:
            best = (float(score), rot)
    return best
