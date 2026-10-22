"""Fit a physical 11x5 mm body to four image edges inside each coarse ROI."""
import cv2
import numpy as np


def fit_legacy(gray, quad, search_mm=(15.,9.), center_px=None, max_shift_mm=.5):
    sx,sy=search_mm
    scale=20.
    w,h=int(round(sx*scale)),int(round(sy*scale))
    src=np.asarray(quad,np.float32)
    dst=np.float32([[0,0],[w-1,0],[w-1,h-1],[0,h-1]])
    matrix=cv2.getPerspectiveTransform(src,dst)
    patch=cv2.warpPerspective(gray,matrix,(w,h),flags=cv2.INTER_LINEAR)
    patch=cv2.GaussianBlur(patch.astype(np.float32),(0,0),1.)
    gx=np.abs(cv2.Sobel(patch,cv2.CV_32F,1,0,ksize=3))
    gy=np.abs(cv2.Sobel(patch,cv2.CV_32F,0,1,ksize=3))
    scale_x,scale_y=(w-1)/sx,(h-1)/sy
    hw,hh=11*scale_x/2,5*scale_y/2
    # Average central 75% of each edge; code texture cannot substitute for all four sides.
    vx=cv2.boxFilter(gx,-1,(3,int(2*hh*.75)),normalize=True)
    vy=cv2.boxFilter(gy,-1,(int(2*hw*.75),3),normalize=True)
    cx,cy=(w-1)/2,(h-1)/2
    if center_px is not None:
        cx,cy=cv2.perspectiveTransform(np.float32(center_px).reshape(1,1,2),matrix)[0,0].astype(float)
    xs=cx+np.arange(-int(max_shift_mm*scale_x),int(max_shift_mm*scale_x)+1)
    ys=cy+np.arange(-int(max_shift_mm*scale_y),int(max_shift_mm*scale_y)+1)
    xs=xs[(xs>=hw+2)&(xs<=w-hw-2)]
    ys=ys[(ys>=hh+2)&(ys<=h-hh-2)]
    if len(xs)==0 or len(ys)==0:raise ValueError('Search area smaller than 11x5 mm body')
    xx,yy=np.meshgrid(xs,ys)
    xi,yi=xx.astype(int),yy.astype(int)
    rails=np.stack([vx[yi,(xx-hw).astype(int)],vx[yi,(xx+hw).astype(int)],vy[(yy-hh).astype(int),xi],vy[(yy+hh).astype(int),xi]])
    noise=max(2.,float(np.percentile(np.concatenate((gx.ravel(),gy.ravel())),50)))
    displacement=np.hypot((xx-cx)/scale_x,(yy-cy)/scale_y)
    score=(rails.min(0)+.25*rails.mean(0))/noise-.5*displacement
    score=np.where(displacement<=max_shift_mm+1e-9,score,-np.inf)
    iy,ix=np.unravel_index(np.argmax(score),score.shape)
    detected=bool(score[iy,ix]>=3. and rails[:,iy,ix].min()>=max(5.,noise*1.5))
    x,y=(float(xx[iy,ix]),float(yy[iy,ix])) if detected else (cx,cy)
    nominal=np.float32([[cx-hw,cy-hh],[cx+hw,cy-hh],[cx+hw,cy+hh],[cx-hw,cy+hh]])
    local=np.float32([[x-hw,y-hh],[x+hw,y-hh],[x+hw,y+hh],[x-hw,y+hh]])
    fitted=cv2.perspectiveTransform(local.reshape(1,4,2),np.linalg.inv(matrix))[0]
    nominal=cv2.perspectiveTransform(nominal.reshape(1,4,2),np.linalg.inv(matrix))[0]
    return dict(quad_px=fitted.tolist(),nominal_quad_px=nominal.tolist(),detected=detected,
                score=float(score[iy,ix]),dimensions_mm=[11,5],max_shift_mm=max_shift_mm,
                shift_mm=[(x-cx)/scale_x,(y-cy)/scale_y],method='registered_center_bounded_edges')


def fit(gray, quad, search_mm=(15.,9.), center_px=None, max_shift_mm=.5):
    """Bounded physical rectangle fit, using persistent signed edge evidence.

    Texture inside the core is excluded: samples straddle the expected perimeter.
    Both brightness polarities are accepted, but isolated bright pads cannot vote
    as an entire side. Nominal dimensions constrain a small independent rail fit.
    """
    sx, sy = search_mm
    w, h = int(round(sx*20)), int(round(sy*20))
    matrix = cv2.getPerspectiveTransform(np.float32(quad),
        np.float32([[0,0],[w-1,0],[w-1,h-1],[0,h-1]]))
    patch = cv2.warpPerspective(np.asarray(gray,np.float32), matrix,(w,h))
    # Log intensity is the same illumination-compression principle as the code
    # localizer. Subtract slow background, preserving the two-sided edge sign.
    log = np.log(np.maximum(patch,0)+64.)
    signed = cv2.GaussianBlur(log,(0,0),.8)-cv2.GaussianBlur(log,(0,0),24.)
    scale = np.array([(w-1)/sx,(h-1)/sy])
    center = np.array([(w-1)/2,(h-1)/2])
    if center_px is not None:
        center = cv2.perspectiveTransform(np.float32(center_px).reshape(1,1,2),matrix)[0,0].astype(float)
    steps = np.arange(-max_shift_mm,max_shift_mm+.00001,.05)
    shifts = np.array([(x,y) for x in steps for y in steps if np.hypot(x,y)<=max_shift_mm+1e-9])
    centers = center+shifts*scale
    best = None
    t = np.linspace(-.85,.85,48)
    # Use four separate sides; exclude rounded corners and solder surroundings.
    sides = [(np.column_stack((t*5.5,np.full_like(t,-2.5))),[0,-1]),
             (np.column_stack((np.full_like(t,5.5),t*2.5)),[1,0]),
             (np.column_stack((t*5.5,np.full_like(t,2.5))),[0,1]),
             (np.column_stack((np.full_like(t,-5.5),t*2.5)),[-1,0])]
    # Normalize confidence by local high frequency noise, not by overall contrast.
    noise = max(.003,float(np.median(np.abs(log-cv2.GaussianBlur(log,(0,0),.8))))*1.4826)
    for angle in np.arange(-4.,4.01,.5):
        a=np.radians(angle); rotation=np.array([[np.cos(a),-np.sin(a)],[np.sin(a),np.cos(a)]])
        evidence=[]; coverage=[]; offsets=[]
        for points,normal in sides:
            normal=np.asarray(normal)
            strengths=[]; coverages=[]
            rail_offsets=np.arange(-.4,.401,.1)
            for offset_mm in rail_offsets:
                values=[]
                for depth in (.12,.24):
                    inside=(points+(offset_mm-depth)*normal)@rotation.T*scale
                    outside=(points+(offset_mm+depth)*normal)@rotation.T*scale
                    def sample(offset):
                        coords=centers[:,None,:]+offset[None,:,:]
                        return cv2.remap(signed,np.float32(coords[:,:,0]),np.float32(coords[:,:,1]),cv2.INTER_LINEAR)
                    values.append(sample(inside)-sample(outside))
                delta=np.mean(values,axis=0)
                polarity=np.where(np.median(delta,axis=1)>=0,1.,-1.)
                consistent=delta*polarity[:,None]
                strengths.append(np.maximum(0,np.percentile(consistent,30,axis=1))/noise)
                coverages.append(np.mean(consistent>noise*2,axis=1))
            strengths=np.asarray(strengths); coverages=np.asarray(coverages)
            choice=np.argmax(strengths-.3*np.abs(rail_offsets[:,None]),axis=0)
            evidence.append(strengths[choice,np.arange(len(centers))])
            coverage.append(coverages[choice,np.arange(len(centers))])
            offsets.append(rail_offsets[choice])
        rails=np.asarray(evidence); covered=np.asarray(coverage)
        offsets=np.asarray(offsets)
        local_shift=np.column_stack(((offsets[1]-offsets[3])/2,(offsets[2]-offsets[0])/2))
        actual_shifts=shifts+local_shift@rotation.T
        valid=np.linalg.norm(actual_shifts,axis=1)<=max_shift_mm+1e-9
        # A very strong neighboring pad on one side must never compensate for
        # an unsupported opposite edge. Saturate the secondary contribution.
        score=rails.min(0)+.1*np.mean(np.minimum(rails,6),axis=0)-.3*np.linalg.norm(shifts,axis=1)-.01*abs(angle)
        score=np.where(valid,score,-np.inf)
        index=int(np.argmax(score))
        if best is None or score[index]>best[0]:
            best=(float(score[index]),index,float(angle),rails[:,index],covered[:,index],offsets[:,index])
    score,index,angle,rails,coverage,offsets=best
    detected=bool(np.isfinite(score) and rails.min()>=2.5 and coverage.min()>=.6)
    score=score if np.isfinite(score) else 0.
    shift=shifts[index] if detected else np.zeros(2)
    offsets=offsets if detected else np.zeros(4)
    angle=angle if detected else 0.
    a=np.radians(angle);rotation=np.array([[np.cos(a),-np.sin(a)],[np.sin(a),np.cos(a)]])
    corners=np.array([[-5.5,-2.5],[5.5,-2.5],[5.5,2.5],[-5.5,2.5]])
    fitted_corners=corners+np.array([[-offsets[3],-offsets[0]],[offsets[1],-offsets[0]],
                                    [offsets[1],offsets[2]],[-offsets[3],offsets[2]]])
    actual_shift=shift+np.array([(offsets[1]-offsets[3])/2,(offsets[2]-offsets[0])/2])@rotation.T
    inverse=np.linalg.inv(matrix)
    def project(points):
        return cv2.perspectiveTransform(np.float32(points).reshape(1,4,2),inverse)[0].tolist()
    return dict(quad_px=project((fitted_corners@rotation.T+shift)*scale+center),
        nominal_quad_px=project(corners*scale+center),detected=detected,score=score,
        dimensions_mm=[11+float(offsets[1]+offsets[3]),5+float(offsets[0]+offsets[2])],
        nominal_dimensions_mm=[11,5],rail_tolerance_mm=.4,
        max_shift_mm=max_shift_mm,shift_mm=actual_shift.tolist(),angle_deg=angle,
        side_strength=rails.tolist(),side_coverage=coverage.tolist(),
        method='registered_signed_persistent_edges')


def refine(registration, image_path):
    gray=cv2.imread(str(image_path),cv2.IMREAD_GRAYSCALE)
    if gray is None:raise ValueError('Reference image unavailable for body fitting')
    h,w=gray.shape
    if (w,h)!=(registration['width'],registration['height']):raise ValueError('Reference dimensions changed')
    for part in registration['parts']:
        quad=np.asarray(part['quad_normalized'])*[w,h]
        result=fit(gray,quad,(registration['roi_width_mm'],registration['roi_height_mm']),part['center_px'])
        part['body_nominal_quad_normalized']=(np.asarray(result.pop('nominal_quad_px'))/[w,h]).tolist()
        part['body_quad_normalized']=(np.asarray(result.pop('quad_px'))/[w,h]).tolist()
        part['body_fit']=result
    registration['body_outline_only']=True
    registration['body_dimensions_mm']=[11,5]
    registration['body_dimension_mode']='nominal_11x5_independent_rails_0.4mm'
    return registration
