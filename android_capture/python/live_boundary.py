"""Live projective grid candidates, using engraved v1.2 stages A-F.

YUV preview luminance is NOT developed sensor RAW. Grid dimensions are inferred; no decoder,
reference payload or quiet-zone fallback is run. All displayed quads are provisional.
"""
import json
import time
import cv2
import numpy as np
import engraved_geometry as eg
from symbol_sizes import SYMBOL_SHAPES

cv2.setNumThreads(1)


def locate_gray(gray, source_width=None, known_body=False):
    gray=np.asarray(gray,np.float32)
    if gray.ndim!=2 or min(gray.shape)<(20 if known_body else 80) or not np.isfinite(gray).all():
        return dict(status='no_boundary',reason='invalid_frame')
    height,width=gray.shape
    scale=(width if source_width is None else source_width)/4080.0
    odd=lambda v:max(1,int(round(v*scale))//2*2+1)
    p=dict(eg.DEFAULTS)
    p.update(part_min_side=max(20,80*scale),min_symbol_side=max(12,30*scale),
             part_inset=max(3,int(round(12*scale))),tophat_size=max(31,odd(121)),
             part_openings=tuple(odd(v) if v>1 else 1 for v in (1,11,21)),
             dog=(max(.5,1.5*scale),max(1.,4*scale)),energy_sigma=max(3.,8*scale),
             bg_sigma=max(5.,15*scale),dark_sigma=max(.4,.8*scale),
             wall_search=(-max(3,10*scale),max(3,8*scale)),max_part_candidates=3)
    if known_body:
        p.update(part_scale=1,part_inset=0)
        parts=[dict(bbox=[0,0,width,height],_blob=np.ones((height,width),np.uint8),_blob_origin=(0,0))]
    else:
        parts=eg.find_parts(gray,p)
    candidates=[]
    for part in parts:
        try:
            an=eg.analyse(gray,part,p)
            if an['coherence']<.04 or an['energy_peak_z']<4:
                continue
            x0,y0=an['origin'];hh,ww=an['dark'].shape
            local=gray[y0:y0+hh,x0:x0+ww]
            an['planes']['illum8']=cv2.GaussianBlur(local,(0,0),8.)-local
            for plane in ('signed','absdev','illum8'):
                aa=dict(an,dark=an['planes'][plane])
                walls=eg.first_walls(aa,p)
                walls,L=eg.l_walls(aa,walls,p)
                quad=eg.quad_of(walls)
                if not np.isfinite(quad).all() or not cv2.isContourConvex(quad.astype(np.float32)):
                    continue
                sides=np.linalg.norm(quad-np.roll(quad,-1,axis=0),axis=1)
                if min(sides)<p['min_symbol_side'] or max(sides)/min(sides)>3.5:
                    continue
                x,y=an['origin'];native=quad+[x,y]
                if (native<0).any() or (native[:,0]>=width).any() or (native[:,1]>=height).any():
                    continue
                # Do not draw boundaries extending beyond the detected part.
                if any(eg.bilinear(an['mask'].astype(np.float32),quad[:,0],quad[:,1])<.3):
                    continue
                contrasts=np.array([walls[k]['contrast'] for k in eg.ORDER])
                if min(contrasts)<=0 or min(contrasts)/max(contrasts)<.08:
                    continue
                score=float(np.mean(contrasts)*an['coherence'])
                candidates.append(dict(score=score,_dark=aa['dark'],_quad=quad,_origin=np.array([x,y]),quad_normalized=(native/[width,height]).tolist(),
                                       plane=plane,orientation_coherence=an['coherence']))
        except (LookupError,ValueError,np.linalg.LinAlgError,cv2.error,FloatingPointError):
            continue
    routes=[]
    for name,allowed in (('LOC_current',('signed','absdev')),('LOC_signed',('signed',)),('LOC_absdev',('absdev',)),('LOC_illum8',('illum8',))):
        subset=[c for c in candidates if c['plane'] in allowed]
        if subset:
            chosen=max(subset,key=lambda c:c['score'])
            routes.append(dict(name=name,status='boundary_candidate',quad_normalized=chosen['quad_normalized'],plane=chosen['plane']))
        else:routes.append(dict(name=name,status='no_boundary'))
    baseline=[c for c in candidates if c['plane'] in ('signed','absdev')]
    if not baseline:
        return dict(status='no_boundary',part_count=len(parts),localizations=routes)
    best=dict(max(baseline,key=lambda c:c['score']))
    dark=best.pop('_dark');quad=best.pop('_quad');best.pop('_origin')
    # Optional grid never rejects, moves, or re-ranks the successful boundary.
    try:
        grids=[]
        for rows,cols in SYMBOL_SHAPES:
            if max(rows,cols)>40:continue
            vals=eg.sample_board(dark,quad,rows,cols,p['cell_inset'])
            score,_=eg.finder_conformance((vals>eg.two_means(vals.ravel())).astype(np.uint8))
            grids.append(dict(rows=rows,columns=cols,finder_conformance=score))
        grids.sort(key=lambda g:(-g['finder_conformance'],g['rows']*g['columns']))
        grid=grids[0];gap=grid['finder_conformance']-grids[1]['finder_conformance']
        best['grid']=dict(grid,score_gap=gap,reliable=bool(grid['finder_conformance']>=.9 and gap>=.025))
    except (ValueError,np.linalg.LinAlgError,cv2.error,FloatingPointError) as exc:
        best['grid_error']=type(exc).__name__
    return dict(status='boundary_candidate',**best,part_count=len(parts),localizations=routes)


def analyze(data,width,height,roi_json='null'):
    started=time.perf_counter()
    raw=np.frombuffer(bytes(data),dtype=np.uint8)
    if len(raw)!=width*height:
        raise ValueError('Packed Y size mismatch')
    # Preserve delivered preview samples: scale only for numerical range compatibility.
    import touch_roi
    result=touch_roi.locate(raw.reshape(height,width).astype(np.float32)*257.,roi_json,locate_gray)
    result.update(seconds=time.perf_counter()-started,width=width,height=height,
                  mode='preview_Y_boundary_with_optional_grid',grid_or_decode_run=True,decode_run=False)
    return json.dumps(result,allow_nan=False)


def analyze_file(path):
    a=cv2.imread(path,cv2.IMREAD_UNCHANGED)
    if a is None:raise ValueError('Cannot read fixture')
    if a.ndim==3:a=a.astype(np.float32).mean(2)
    start=time.perf_counter();r=locate_gray(a)
    r['seconds']=time.perf_counter()-start
    return json.dumps(r,allow_nan=False)


FILTER_NAMES=('original','unsharp_s1.2_a1.5','unsharp_s2_a1','unsharp_s0.9_a3','illumination_sigma8','gaussian_s0.6')

def prepare_filters(data,width,height,quad_json):
    gray=np.frombuffer(bytes(data),np.uint8).reshape(height,width).astype(np.float32)
    quad=np.array(json.loads(quad_json),np.float32)*[width,height]
    lo=np.maximum(np.floor(quad.min(0)-32).astype(int),0)
    hi=np.minimum(np.ceil(quad.max(0)+33).astype(int),[width,height])
    return gray[lo[1]:hi[1],lo[0]:hi[0]].copy(),quad-lo

def filter_step(state,name):
    start=time.perf_counter();gray,quad=state
    if name=='original':plane=gray
    elif name.startswith('unsharp_'):
        sigma,amount={'unsharp_s1.2_a1.5':(1.2,1.5),'unsharp_s2_a1':(2.,1.),'unsharp_s0.9_a3':(.9,3.)}[name]
        plane=np.clip(gray+amount*(gray-cv2.GaussianBlur(gray,(0,0),sigma)),0,255)
    elif name=='illumination_sigma8':plane=gray-cv2.GaussianBlur(gray,(0,0),8.)+128
    elif name=='gaussian_s0.6':plane=cv2.GaussianBlur(gray,(0,0),.6)
    else:raise ValueError(name)
    cands=[]
    for rows,cols in SYMBOL_SHAPES:
        if max(rows,cols)>40:continue
        vals=eg.sample_board(plane,quad,rows,cols,.25)
        base=(vals<eg.two_means(vals.ravel())).astype(np.uint8)
        options=[]
        for board in (base,1-base):
            score,_=eg.finder_conformance(board);options.append((score,board))
        score,board=max(options,key=lambda v:v[0])
        cands.append((score,rows,cols,board))
    cands.sort(key=lambda v:(-v[0],v[1]*v[2]))
    score,rows,cols,board=cands[0];gap=score-cands[1][0]
    return json.dumps(dict(name=name,rows=rows,columns=cols,finder_conformance=score,score_gap=gap,
        success=bool(score>=.9 and gap>=.025),board=board.tolist(),seconds=time.perf_counter()-start,
        decoded=False),allow_nan=False)


def preview_channel(y,u,v,width,height,channel):
    if channel=='FULL':return bytes(y)
    # Android YUV_420_888 packed planar I420; OpenCV uses BT.601 limited-range.
    yy=np.frombuffer(bytes(y),np.uint8)
    uu=np.frombuffer(bytes(u),np.uint8);vv=np.frombuffer(bytes(v),np.uint8)
    planar=np.concatenate((yy,uu,vv)).reshape(height*3//2,width)
    rgb=cv2.cvtColor(planar,cv2.COLOR_YUV2RGB_I420)
    return rgb[:,:,{'R':0,'G':1,'B':2}[channel]].tobytes()
