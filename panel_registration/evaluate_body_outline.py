"""Offline comparison on original reference pixels; never changes phone state."""
import argparse
import json
import time
from pathlib import Path

import cv2
import numpy as np

from body_outline import fit, fit_legacy


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('image', type=Path)
    parser.add_argument('registration', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    image = cv2.imread(str(args.image), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError('Image unavailable')
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    registration = json.loads(args.registration.read_text(encoding='utf-8'))
    height, width = gray.shape
    if (width, height) != (registration['width'], registration['height']):
        raise ValueError('Reference dimensions differ')
    args.output.mkdir(parents=True, exist_ok=True)
    results = {}
    for name, fitter in [('legacy', fit_legacy), ('signed_edges', fit)]:
        canvas = image.copy()
        start = time.perf_counter()
        parts = []
        for part in registration.get('all_parts', registration['parts']):
            result = fitter(gray, np.asarray(part['quad_normalized'])*[width, height],
                            (registration['roi_width_mm'], registration['roi_height_mm']),
                            part['center_px'])
            parts.append(dict(designator=part['designator'], **result))
            color = (0,255,0) if result['detected'] else (0,180,255)
            cv2.polylines(canvas, [np.int32(result['quad_px'])], True, color, 2)
            cv2.putText(canvas, part['designator'], tuple(np.int32(part['center_px'])),
                        cv2.FONT_HERSHEY_SIMPLEX, .45, (0,0,255), 1)
        results[name] = dict(seconds=time.perf_counter()-start, parts=parts,
                             accepted=sum(p['detected'] for p in parts))
        cv2.imwrite(str(args.output/(name+'.png')), canvas)
    (args.output/'comparison.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
    print(json.dumps({k:dict(accepted=v['accepted'],seconds=v['seconds'])
                      for k,v in results.items()}))


if __name__ == '__main__':
    main()
