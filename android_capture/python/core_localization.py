"""Consultant body/surface/window localization, isolated from ALL grid/decoder calls.
Adapted from handoffs/2026-10-21_core_and_code_localization/code (v1.0).
Only geometric first_walls/l_walls use our existing engraved_geometry module.
"""
import cv2
import numpy as np
import engraved_geometry as eg


def locate(gray, homography, body_quad):
    """Return refined body and code routes in ORIGINAL image pixels. No grid."""
    body=refine_body(gray,homography,body_quad)
    if body['status']!='ok':
        return dict(status='no_boundary',body_status=body['status'],localizations=[])
    img,C,corners,s=body['_img'],body['_C'],body['_corners'],body['px_per_mm']
    center=corners.mean(0);lu=body['_long_u']
    angle=(float(np.degrees(np.arctan2(lu[1],lu[0])))+90)%180-90
    mask=np.zeros(img.shape,np.uint8);cv2.fillConvexPoly(mask,np.int32(np.round(corners)),1)
    core=dict(rect=((float(center[0]),float(center[1])),(body['_long_len'],body['_short_len']),angle),mask=mask>0)
    windows,rot,R,stats=code_windows(img,core,s,WINDOW_DEFAULTS)
    rotated_mask=cv2.warpAffine(mask,R,img.shape[::-1],flags=cv2.INTER_NEAREST)>0
    result=dict(status='no_boundary',body_status='ok',body_quad_px=body['corners_px'],
                body_size_mm=body['size_mm'],body_edge_residual_px=body['edge_residual_px'],
                body_edge_shift_fraction=body['edge_shift_fraction'],body_max_edge_fraction=body['max_edge_fraction'],
                code_search_order='center_out',localizations=[])
    if not windows:return result
    inverse=cv2.invertAffineTransform(R)
    def native(q):return homography_apply(C,np.column_stack((q,np.ones(4)))@inverse.T)
    routes=[]
    for win_index,win in enumerate(windows):
        x0,y0,x1,y1=win['box']
        window=np.array([[x0,y0],[x1,y0],[x1,y1],[x0,y1]],float)
        lg=np.log(rot+1.)
        dark=float(np.median(cv2.GaussianBlur(lg,(0,0),.06*s)[stats['surface_mask']]))-cv2.GaussianBlur(lg,(0,0),.06*s)
        inside=dark[y0:y1,x0:x1];polarity=1. if np.mean(np.abs(np.minimum(inside,0)))<np.mean(np.maximum(inside,0)) else -1.
        fitted=[]
        illum=cv2.GaussianBlur(lg,(0,0),max(1.,.4*s))-lg
        for plane,d in [('signed',dark*polarity),('absdev',np.abs(dark)),('illum',illum*polarity)]:
            region=np.zeros(rot.shape,bool);region[y0:y1,x0:x1]=True
            an=dict(dark=np.float32(d),U=np.array([1.,0.]),V=np.array([0.,1.]),
                    ext=dict(u0=x0,u1=x1,v0=y0,v1=y1),mask=rotated_mask,region=region)
            p=dict(eg.DEFAULTS,wall_search=(-.5*s,.5*s),wall_angle=3.)
            try:
                walls=eg.first_walls(an,p)
                alternatives=[('walls',walls)]
                try:alternatives.append(('L',eg.l_walls(an,walls,p)[0]))
                except (LookupError,ValueError,np.linalg.LinAlgError,cv2.error,FloatingPointError):pass
                noise=max(1e-4,float(np.median(np.abs(d[stats['surface_mask']]-np.median(d[stats['surface_mask']]))))*1.4826)
                for kind,w in alternatives:
                    q=eg.quad_of(w);sides=np.linalg.norm(q-np.roll(q,-1,axis=0),axis=1)/s
                    contrasts=np.array([w[k]['contrast'] for k in eg.ORDER])
                    if np.isfinite(q).all() and cv2.isContourConvex(np.float32(q)) and sides.min()>=2.5 and sides.max()<=6.5 and sides.max()/sides.min()<=2. and contrasts.min()>0 and np.all(eg.bilinear(np.float32(rotated_mask),q[:,0],q[:,1])>.3):
                        score=float((contrasts.min()+.2*contrasts.mean())/noise)
                        fitted.append((plane+'_'+kind,q,score))
            except (LookupError,ValueError,np.linalg.LinAlgError,cv2.error,FloatingPointError):pass
        # All geometry candidates go through the EXISTING Java grid/decoder.
        # Always retain square window as a separate sampling candidate.
        fitted.sort(key=lambda item:-item[2])
        for name,q,_ in fitted+[('window',window,0.)]:
            routes.append(dict(name='CORE_CODE_'+str(win_index)+'_'+name,status='boundary_candidate',quad_px=native(q).tolist()))
        if win_index==0:
            # The square only seeds the search. Grid samples follow independent
            # fitted edges (a general quadrilateral), not a forced square.
            primary=fitted[0][1] if fitted else window
            qcan=np.column_stack((primary,np.ones(4)))@inverse.T
            delta=qcan.mean(0)-center
            result.update(status='boundary_candidate',quad_px=native(primary).tolist(),
                          code_center_px=native(primary).mean(0).tolist(),window_z=win['z'],
                          offset_in_body_mm=[float(delta@body['_long_u']/s),float(delta@body['_short_u']/s)],
                          boundary='edge_refined_quad' if fitted else 'code_window_fallback',
                          edge_plane=fitted[0][0] if fitted else None)
    result['localizations']=routes
    return result
DEFAULTS = dict(canonical_half_mm=(8.5,5.5),edge_search_mm=.4,edge_profiles=40,edge_smooth_px=.6,max_edge_fraction=.05)
WINDOW_DEFAULTS = dict(code_mm=(3.6,5.),code_step_mm=.1,code_pos_step_mm=.1,code_inside_tol_mm=.15,min_code_z=1.,rim_mm=.35,extra_code_frac=.6,max_codes=3)

def homography_apply(H, pts):
    pts = np.asarray(pts, np.float64).reshape(-1, 2)
    q = np.column_stack((pts, np.ones(len(pts)))) @ np.asarray(H).T
    return q[:, :2] / q[:, 2:]

def canonical_frame(H_mm, centre_mm, half_mm, px_per_mm):
    """Affine-free mapping canonical pixel (u, v) -> image pixel, u along panel X, v along panel Y."""
    w = int(round(2 * half_mm[0] * px_per_mm))
    h = int(round(2 * half_mm[1] * px_per_mm))
    # canonical pixel -> panel mm
    S = np.array([[1 / px_per_mm, 0, centre_mm[0] - half_mm[0]],
                  [0, 1 / px_per_mm, centre_mm[1] - half_mm[1]],
                  [0, 0, 1]])
    return np.asarray(H_mm) @ S, (w, h)

def local_scale(H_mm, centre_mm):
    p = homography_apply(H_mm, [centre_mm, (centre_mm[0] + 1, centre_mm[1]), (centre_mm[0], centre_mm[1] + 1)])
    return float((np.linalg.norm(p[1] - p[0]) + np.linalg.norm(p[2] - p[0])) / 2)

def center_out_positions(x0,y0,x1,y1,side,cx,cy,step,tol):
    """Exact center first, then increasing radius; retain the full search area."""
    xs=np.arange(x0-tol,x1+tol-side+1e-9,step)
    ys=np.arange(y0-tol,y1+tol-side+1e-9,step)
    xx,yy=np.meshgrid(xs,ys)
    points=np.column_stack((xx.ravel(),yy.ravel()))
    middle=np.array([cx-side/2,cy-side/2])
    if np.all(middle>=[x0-tol,y0-tol]) and np.all(middle<=[x1+tol-side,y1+tol-side]):
        points=np.vstack((middle,points))
    radius=np.sum((points-middle)**2,axis=1)
    return points[np.argsort(radius,kind='stable')]


def code_windows(img, core, s, p):
    """Square windows of physical side within code_mm, inside the core, ranked by |deviation| from the core surface."""
    (cx, cy), (rw, rh), ang = core['rect']
    # rotate the canonical frame so that the core is axis-aligned (small angle)
    R = cv2.getRotationMatrix2D((cx, cy), ang, 1.0)
    rot = cv2.warpAffine(img, R, img.shape[::-1], flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    x0, x1 = cx - rw / 2, cx + rw / 2
    y0, y1 = cy - rh / 2, cy + rh / 2
    core_box = np.zeros(img.shape, np.uint8)
    core_box[int(np.ceil(y0)):int(y1), int(np.ceil(x0)):int(x1)] = 1
    # statistics exclude the bevelled rim; windows may still reach the core edge
    rim = max(1, int(round(p['rim_mm'] * s)))
    core_box = cv2.erode(core_box, np.ones((2 * rim + 1, 2 * rim + 1), np.uint8))
    lg = np.log(rot + 1.0)
    sm = cv2.GaussianBlur(lg, (0, 0), max(0.6, 0.05 * s))
    # robust quadratic model of the core surface (iteratively trimmed, so the mark does not bias it)
    ys, xs = np.nonzero(core_box)
    step = max(1, len(xs) // 15000)
    xs, ys = xs[::step], ys[::step]
    A = np.column_stack((np.ones(len(xs)), xs, ys, xs * xs, xs * ys, ys * ys)).astype(np.float64)
    keep = np.ones(len(xs), bool)
    for _ in range(3):
        coef = np.linalg.lstsq(A[keep], sm[ys[keep], xs[keep]], rcond=None)[0]
        res = np.abs(sm[ys, xs] - A @ coef)
        keep = res < np.percentile(res, 70)
    yy, xx = np.mgrid[0:img.shape[0], 0:img.shape[1]]
    surface = (coef[0] + coef[1] * xx + coef[2] * yy + coef[3] * xx * xx + coef[4] * xx * yy + coef[5] * yy * yy)
    dev = np.abs(sm - surface)
    # A perfectly smooth surface has only float/fit roundoff; normalizing it by
    # near-zero MAD otherwise invents a high-scoring code window.
    if np.percentile(dev[core_box>0],95)<1e-4:
        return [],rot,R,None
    noise = 1.4826 * np.median(res[keep]) + 1e-9
    e = (dev / noise) * core_box
    ii = cv2.integral(e.astype(np.float64))
    ic = cv2.integral(core_box.astype(np.float64))
    tol = p['code_inside_tol_mm'] * s
    found = []
    for side_mm in np.arange(p['code_mm'][0], p['code_mm'][1] + 1e-9, p['code_step_mm']):
        a = side_mm * s
        if a > rh + 2 * tol:
            continue
        for xx,yy in center_out_positions(x0,y0,x1,y1,a,cx,cy,p['code_pos_step_mm']*s,tol):
                X0, Y0, X1, Y1 = int(round(xx)), int(round(yy)), int(round(xx + a)), int(round(yy + a))
                X0, Y0 = max(X0, 0), max(Y0, 0)
                X1, Y1 = min(X1, img.shape[1]), min(Y1, img.shape[0])
                sin_ = ii[Y1, X1] - ii[Y0, X1] - ii[Y1, X0] + ii[Y0, X0]
                nin = ic[Y1, X1] - ic[Y0, X1] - ic[Y1, X0] + ic[Y0, X0]
                found.append((sin_ / max(nin, 1), side_mm, X0, Y0, X1, Y1, nin))
    if not found:
        return [], rot, R, None
    # surface statistics: core pixels outside the best window (robust)
    found.sort(key=lambda f: (-f[0],((f[2]+f[4])/2-cx)**2+((f[3]+f[5])/2-cy)**2))
    _, _, X0, Y0, X1, Y1, _ = found[0]
    surf = core_box.astype(bool).copy()
    surf[Y0:Y1, X0:X1] = False
    surf &= cv2.erode(core_box, np.ones((3, 3), np.uint8)).astype(bool)
    if surf.sum() < 50:
        return [], rot, R, None
    # window-level noise: energy of same-size windows is a mean, so compare to the surface mean/MAD
    ev = e[surf]
    mu, mad = float(np.median(ev)), float(np.median(np.abs(ev - np.median(ev))) * 1.4826 + 1e-12)
    out = []
    for f in found:
        z = (f[0] - mu) / mad
        if z < p['min_code_z']:
            break
        box = f[2:6]
        if any(iou(box, o['box']) > 0.15 for o in out):
            continue
        if out and z < p['extra_code_frac'] * out[0]['z']:
            break
        out.append(dict(z=float(z), side_mm=float(f[1]), box=box))
        if len(out) >= p['max_codes']:
            break
    return out, rot, R, dict(surface_mask=surf, energy=e)

def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    i = ix * iy
    return i / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i)

def _side_fit(lg, p0, p1, outward, s, half_mm, n):
    t = np.linspace(0.15, 0.85, n)
    offs = np.arange(-half_mm * s, half_mm * s + 1e-9, 0.25)
    pts = []
    for ti in t:
        base = p0 + (p1 - p0) * ti
        xy = base[None] + outward[None] * offs[:, None]
        prof = cv2.remap(lg, xy[:, 0].astype(np.float32)[None], xy[:, 1].astype(np.float32)[None], cv2.INTER_LINEAR)[0]
        g = -np.gradient(prof)                          # core top is brighter than its surround
        k = int(np.argmax(g))
        if 0 < k < len(g) - 1 and g[k] > 0:
            den = g[k - 1] - 2 * g[k] + g[k + 1]
            sub = 0.5 * (g[k - 1] - g[k + 1]) / den if den < 0 else 0.0
            pts.append(base + outward * (offs[k] + sub * 0.25))
    pts = np.array(pts)
    if len(pts) < 8:
        return None
    keep = np.ones(len(pts), bool)
    for _ in range(3):
        c = pts[keep].mean(0)
        d = np.linalg.svd(pts[keep] - c)[2][0]
        res = np.abs((pts - c) @ np.array([-d[1], d[0]]))
        keep = res <= max(np.percentile(res, 80), 0.3)
    return dict(point=c, dir=d, residual_px=float(np.median(res[keep])))

def _intersect(a, b):
    t = np.linalg.solve(np.array([a['dir'], -b['dir']]).T, b['point'] - a['point'])
    return a['point'] + a['dir'] * t[0]


def bound_body_corners(initial,candidate,fraction=.05):
    """Bound BOTH endpoints of every side against the original placement.

    Five percent is measured perpendicular to that side, using the initial
    body's extent in the same direction. A common blend also bounds tilted
    edges and intersections; this is never anchored to the previous frame.
    """
    initial=np.asarray(initial,float);candidate=np.asarray(candidate,float)
    normals=[];limits=[];drift=[]
    for i in range(4):
        side=initial[(i+1)%4]-initial[i];normal=np.array([-side[1],side[0]])/np.linalg.norm(side)
        limit=fraction*np.ptp(initial@normal)
        normals.append(normal);limits.append(limit)
        drift.append(np.max(np.abs((candidate[[i,(i+1)%4]]-initial[i])@normal)))
    ratio=max(d/max(l,1e-9) for d,l in zip(drift,limits))
    bounded=initial+(candidate-initial)*min(1.,1./max(ratio,1.))
    if not np.isfinite(bounded).all() or not cv2.isContourConvex(np.float32(bounded)):
        bounded=initial.copy()
    shifts=[float(np.max(np.abs((bounded[[i,(i+1)%4]]-initial[i])@n))/np.ptp(initial@n)) for i,n in enumerate(normals)]
    return bounded,shifts,limits

def refine_body(gray, H_mm_to_px, body_quad_px, params=None):
    """Sub-pixel body outline. Returns dict with corners (image px), size_mm, axes and the canonical frame."""
    p = dict(DEFAULTS, **(params or {}))
    H = np.asarray(H_mm_to_px, float)
    quad = np.asarray(body_quad_px, float)
    c_mm = homography_apply(np.linalg.inv(H), [quad.mean(0)])[0]
    s = local_scale(H, c_mm)
    C, (w, h) = canonical_frame(H, c_mm, p['canonical_half_mm'], s)
    img = cv2.warpPerspective(np.asarray(gray, np.float32), C, (w, h), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP)
    box = homography_apply(np.linalg.inv(C), quad)
    cen = box.mean(0)
    box = box[np.argsort(np.arctan2(box[:, 1] - cen[1], box[:, 0] - cen[0]))]
    lg = cv2.GaussianBlur(np.log(img + 1.0), (0, 0), p['edge_smooth_px'])
    sides = []
    for i in range(4):
        p0, p1 = box[i], box[(i + 1) % 4]
        d = (p1 - p0) / np.linalg.norm(p1 - p0)
        out = (p0 + p1) / 2 - cen
        out = out - d * (out @ d); out /= np.linalg.norm(out)
        limit_mm=p['max_edge_fraction']*np.ptp(box@out)/s
        sides.append(_side_fit(lg, p0, p1, out, s, min(p['edge_search_mm'],limit_mm), p['edge_profiles']))
    if any(x is None for x in sides):
        return dict(status='edge_fit_failed')
    corners = np.array([_intersect(sides[i - 1], sides[i]) for i in range(4)])
    corners,edge_shifts,edge_limits=bound_body_corners(box,corners,p['max_edge_fraction'])
    e0, e1 = corners[1] - corners[0], corners[2] - corners[1]
    long_v, short_v = (e0, e1) if np.linalg.norm(e0) > np.linalg.norm(e1) else (e1, e0)
    long_u = long_v / np.linalg.norm(long_v)
    lens = np.linalg.norm(corners - np.roll(corners, -1, 0), axis=1) / s
    return dict(status='ok', corners_px=homography_apply(C, corners).tolist(),
                center_px=homography_apply(C, [corners.mean(0)])[0].tolist(),
                size_mm=sorted([float((lens[0] + lens[2]) / 2), float((lens[1] + lens[3]) / 2)], reverse=True),
                edge_residual_px=[x['residual_px'] for x in sides],edge_shift_fraction=edge_shifts,
                max_edge_fraction=p['max_edge_fraction'],edge_limit_mm=(np.asarray(edge_limits)/s).tolist(),px_per_mm=s,
                _img=img, _C=C, _corners=corners, _long_u=long_u, _short_u=np.array([-long_u[1], long_u[0]]),
                _long_len=float(np.linalg.norm(long_v)), _short_len=float(np.linalg.norm(short_v)))
