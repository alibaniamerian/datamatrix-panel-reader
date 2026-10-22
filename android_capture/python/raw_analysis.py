"""Native RAW16 sensor analysis. No JPEG/YUV, gamma, auto brightness, WB or 8-bit quantization."""
import json,time
import cv2
import numpy as np
import live_boundary as lb

def prepare(data,width,height,metadata):
    meta=json.loads(metadata)
    raw=np.frombuffer(bytes(data),dtype='<u2').reshape(height,width)
    # CFA refers to sensor origin, including optically black margins.
    layouts={0:('R','G','G','B'),1:('G','R','B','G'),2:('G','B','R','G'),3:('B','G','G','R')}
    cfa=layouts.get(meta['cfa'])
    if cfa is None:raise ValueError('Unsupported non-Bayer CFA')
    ox,oy=meta.get('sensor_origin',[0,0]);cfa=tuple(cfa[((y+oy)%2)*2+(x+ox)%2] for y in range(2) for x in range(2))
    mosaic=raw.astype(np.float32)
    black=meta['black'];white=meta['white']
    for y in range(2):
        for x in range(2):
            offset=black[y*2+x]
            if white<=offset:raise ValueError('Invalid white/black range')
            mosaic[y::2,x::2]=(mosaic[y::2,x::2]-offset)*(65535./(white-offset))
    return dict(mosaic=mosaic,cfa=cfa,meta=meta,cache_name=None,cache=None)

def plane(state,name):
    if state['cache_name']==name:return state['cache']
    a=state['mosaic'];h,w=a.shape
    wanted=('R','G','B') if name=='FULL' else (name,)
    result=np.zeros_like(a)
    kernel=np.array([[1,2,1],[2,4,2],[1,2,1]],np.float32)
    for color in wanted:
        mask=np.zeros_like(a)
        for y in range(2):
            for x in range(2):
                if state['cfa'][y*2+x]==color:mask[y::2,x::2]=1
        weight=cv2.filter2D(mask,-1,kernel,borderType=cv2.BORDER_REFLECT_101)
        interpolated=cv2.filter2D(a*mask,-1,kernel,borderType=cv2.BORDER_REFLECT_101)/np.maximum(weight,1e-12)
        known=mask==1;interpolated[known]=a[known]
        result+=interpolated
    if len(wanted)==3:result/=3.
    x,y,r,b=state['meta']['crop'];result=result[y:b,x:r].copy()
    state['cache_name']=name;state['cache']=result
    return result

def analyze(state,name,roi_json='null'):
    import touch_roi
    start=time.perf_counter();a=plane(state,name);r=touch_roi.locate(a,roi_json,lb.locate_gray)
    r.update(seconds=time.perf_counter()-start,width=a.shape[1],height=a.shape[0],channel=name,
        source='RAW_SENSOR16',mode='linear_sensor_RGB_no_WB_no_gamma',metadata=state['meta'])
    return json.dumps(r,allow_nan=False)

def filter_state(state,name,quad_json):
    a=plane(state,name);h,w=a.shape
    quad=np.array(json.loads(quad_json),np.float32)*[w,h]
    lo=np.maximum(np.floor(quad.min(0)-32).astype(int),0)
    hi=np.minimum(np.ceil(quad.max(0)+33).astype(int),[w,h])
    # Float scale only; no clipping/rounding/8-bit conversion.
    return a[lo[1]:hi[1],lo[0]:hi[0]].copy()/257.,quad-lo
