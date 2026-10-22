"""Crop only the touched search area; return boundaries in original frame coordinates."""
import json
import math
import numpy as np


def locate(gray, roi_json, locator):
    region = json.loads(roi_json)
    if region is None:
        return locator(gray)
    if len(region) != 4 or not all(math.isfinite(v) for v in region):
        raise ValueError('Invalid touch search region')
    left, top, right, bottom = region
    if not 0 <= left < right <= 1 or not 0 <= top < bottom <= 1:
        raise ValueError('Touch search region outside frame')
    height, width = gray.shape
    x0, y0 = int(math.floor(left*width)), int(math.floor(top*height))
    x1, y1 = int(math.ceil(right*width)), int(math.ceil(bottom*height))
    result = locator(gray[y0:y1,x0:x1], source_width=width)
    def remap(quad):
        return ((np.asarray(quad)*[x1-x0,y1-y0]+[x0,y0])/[width,height]).tolist()
    if 'quad_normalized' in result:
        result['quad_normalized'] = remap(result['quad_normalized'])
    for route in result.get('localizations',[]):
        if 'quad_normalized' in route:
            route['quad_normalized'] = remap(route['quad_normalized'])
    result['search_roi_normalized'] = region
    result['search_roi_px'] = [x0,y0,x1,y1]
    result['search_mode'] = 'touch_only'
    return result
