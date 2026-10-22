"""Use registered component ROIs; infer each symbol boundary and dimensions from pixels."""
import json
import time
import cv2
import numpy as np
import engraved_geometry as eg
import live_boundary as lb
import panel_pose

_geometry_cache={}


def analyze(data, width, height, config_json, target_json):
    start = time.perf_counter()
    config = json.loads(config_json)
    if config['width'] != width or config['height'] != height or config['source'] != 'YUV':
        raise ValueError('Panel registration geometry mismatch')
    values = np.frombuffer(bytes(data), np.uint8)
    if values.size != width * height:
        raise ValueError('Panel luma size mismatch')
    source = values.reshape(height, width)
    pose = dict(status='disabled',reason='manual_alignment') if config.get('manual_alignment') else panel_pose.check(source, config) if config.get('require_pose_check') else dict(status='not_requested')
    if pose['status'] in ('moved', 'unverified'):
        return json.dumps(dict(status='registration_moved' if pose['status']=='moved' else 'no_boundary',
                               localizations=[], symbols=[], seconds=time.perf_counter()-start,
                               width=width, height=height, mode='registered_component_roi',
                               pose=pose, registered_component_count=len(config['parts'])), allow_nan=False)
    return locate(source.astype(np.float32) * 257., config, target_json, start, pose)


def analyze_raw(state, channel, config_json, target_json):
    import raw_analysis
    start = time.perf_counter()
    config = json.loads(config_json)
    x, y, right, bottom = state['meta']['crop']
    width, height = right-x, bottom-y
    # Compare field of view against the actual YUV reference before reusing normalized ROIs.
    if abs(width/height-config['width']/config['height']) > .005:
        raise ValueError('RAW/reference aspect ratio mismatch')
    key = config.get('capture_id')
    if config.get('manual_alignment'):
        state['_panel_pose'] = (key,dict(status='disabled',reason='manual_alignment'))
    cached = state.get('_panel_pose')
    if cached is not None and cached[0] == key:
        pose = cached[1]
    else:
        full = raw_analysis.plane(state, 'FULL')
        guard = cv2.resize(full, (config['width'], config['height']), interpolation=cv2.INTER_AREA)
        low, high = np.percentile(guard, [1, 99])
        guard = np.clip((guard-low)*(255./max(1., high-low)), 0, 255).astype(np.uint8)
        pose = panel_pose.check(guard, config)
        pose['guard_source'] = 'RAW_FULL_reference_geometry'
        state['_panel_pose'] = (key, pose)
    if pose['status'] != 'aligned' and not config.get('manual_alignment'):
        return json.dumps(dict(status='registration_moved' if pose['status']=='moved' else 'no_boundary',
                               localizations=[], symbols=[], seconds=time.perf_counter()-start,
                               width=width,height=height,mode='registered_component_roi',pose=pose,
                               registered_component_count=len(config['parts'])),allow_nan=False)
    gray = raw_analysis.plane(state, channel)
    config['_reference_width']=config['width'];config['_reference_height']=config['height']
    config.update(width=width,height=height)
    return locate(gray,config,target_json,start,pose)


def locate(gray, config, target_json, start, pose):
    height, width = gray.shape
    parts = config['parts']
    target = json.loads(target_json)
    if target is None:
        index = 0
    else:
        center = np.asarray(target).reshape(4, 2).mean(0)
        index = int(np.argmin([np.linalg.norm(np.asarray(part['quad_normalized']).mean(0)-center) for part in parts]))
    part = parts[index]
    if 'homography_mm_to_native_px' in config and 'body_quad_normalized' in part:
        import core_localization
        # RAW has the same registered field of view but more pixels than YUV.
        reference_w=config.get('_reference_width',width);reference_h=config.get('_reference_height',height)
        matrix=np.diag([width/reference_w,height/reference_h,1.])@np.asarray(config['homography_mm_to_native_px'],float)
        cache_key=(config.get('capture_id'),config.get('calibration_id'),part['designator'],str(part['body_quad_normalized']))
        now=time.monotonic()
        entry=_geometry_cache.get(cache_key)
        fresh=config.get('fresh_geometry_per_attempt',True)
        cached=entry[1] if not fresh and entry is not None and now-entry[0]<10. else None
        if cached is not None:
            result=json.loads(cached)
            result['geometry_reused']=True
            result['code_center_px']=(np.asarray(result['quad_normalized']).mean(0)*[width,height]).tolist()
        else:
            result=core_localization.locate(gray,matrix,np.asarray(part['body_quad_normalized'])*[width,height])
        for key in ('quad','body_quad'):
            px=result.pop(key+'_px',None)
            if px is not None:result[key+'_normalized']=(np.asarray(px)/[width,height]).tolist()
        for route in result['localizations']:
            if 'quad_px' in route:route['quad_normalized']=(np.asarray(route.pop('quad_px'))/[width,height]).tolist()
        if not fresh and cached is None and result.get('status')=='boundary_candidate' and result.get('window_z',0)>=3:
            if len(_geometry_cache)>500:_geometry_cache.clear()
            _geometry_cache[cache_key]=(time.monotonic(),json.dumps(result,allow_nan=False))
        result['geometry_age_seconds']=0. if cached is None else now-entry[0]
        result['geometry_retry_seconds']=10.
        result['fresh_geometry_per_attempt']=fresh
        result['geometry_retry_seconds']=0. if fresh else 10.
        result.update(seconds=time.perf_counter()-start,width=width,height=height,
                      mode='registered_component_roi',component_id=part['designator'],component_index=index,
                      registered_component_count=len(parts),pose=pose,localizer='consultant_body_surface_window_no_decode')
        result['symbols']=[dict(result)] if result['status']=='boundary_candidate' else []
        return json.dumps(result,allow_nan=False)
    # Same crop-first localizer as the user-verified single-code touch procedure.
    import touch_roi
    quad = np.asarray(part.get('body_quad_normalized',part['quad_normalized']),np.float64)
    lo = np.maximum(quad.min(0),0)
    hi = np.minimum(quad.max(0),1)
    region = [float(lo[0]),float(lo[1]),float(hi[0]),float(hi[1])]
    def localize(crop, source_width):
        return lb.locate_gray(crop,source_width=source_width,known_body='body_quad_normalized' in part)
    result = touch_roi.locate(gray,json.dumps(region),localize)
    result.update(seconds=time.perf_counter()-start,width=width,height=height,
                  mode='registered_component_roi',component_id=part['designator'],
                  component_index=index,registered_component_count=len(parts),pose=pose,
                  localizer='single_code_touch_crop')
    result['symbols'] = [dict(result)] if result.get('status')=='boundary_candidate' else []
    return json.dumps(result,allow_nan=False)
