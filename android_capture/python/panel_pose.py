"""Reference-to-current geometric validation; derived half-size images are guard-only."""
import cv2
import numpy as np

_cache = None
_moved_key = None
_moved_count = 0


def check(gray, config):
    global _moved_key, _moved_count
    result = _measure(gray, config)
    key = config['capture_id']
    if key != _moved_key or result['status'] != 'moved':
        _moved_count = 0
    _moved_key = key
    if result['status'] == 'moved':
        _moved_count += 1
        result['consecutive_moved_frames'] = _moved_count
        if _moved_count < 3:
            result['status'] = 'unverified'
            result['reason'] = 'confirming_motion'
    return result


def _measure(gray, config):
    global _cache
    key = (config['capture_id'], config['reference_path'], gray.shape)
    scale = min(1., 960./gray.shape[1])
    current = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA) if scale < 1 else gray
    orb = cv2.ORB_create(nfeatures=2500)
    if _cache is None or _cache[0] != key:
        reference = cv2.imread(config['reference_path'], cv2.IMREAD_GRAYSCALE)
        if reference is None or reference.shape != gray.shape:
            return dict(status='unverified', reason='reference_geometry')
        if scale < 1:
            reference = cv2.resize(reference, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
        points, descriptors = orb.detectAndCompute(reference, None)
        _cache = (key, points, descriptors)
    _, source_points, source_descriptors = _cache
    current_points, current_descriptors = orb.detectAndCompute(current, None)
    if source_descriptors is None or current_descriptors is None:
        return dict(status='unverified', reason='no_features')
    pairs = cv2.BFMatcher(cv2.NORM_HAMMING).knnMatch(source_descriptors, current_descriptors, k=2)
    matches = [a for pair in pairs if len(pair) == 2 for a, b in [pair] if a.distance < .7*b.distance]
    if len(matches) < 20:
        return dict(status='unverified', reason='few_matches', matches=len(matches))
    a = np.float32([source_points[m.queryIdx].pt for m in matches])
    b = np.float32([current_points[m.trainIdx].pt for m in matches])
    matrix, mask = cv2.findHomography(a, b, cv2.RANSAC, 2.)
    if matrix is None or mask is None:
        return dict(status='unverified', reason='no_transform')
    keep = mask.ravel() != 0
    if keep.sum() < 20 or keep.mean() < .35:
        return dict(status='unverified', reason='weak_transform', matches=len(matches), inliers=int(keep.sum()))
    cover = np.ptp(a[keep], axis=0)
    if cover[0]*cover[1] < current.shape[0]*current.shape[1]*.15:
        return dict(status='unverified', reason='localized_matches', inliers=int(keep.sum()))
    height, width = gray.shape
    centers = np.float32([p['center_normalized'] for p in config['parts']]) * [width*scale, height*scale]
    projected = cv2.perspectiveTransform(centers.astype(np.float32).reshape(1, -1, 2), matrix)[0]
    delta = np.linalg.norm(projected-centers, axis=1)/scale
    if not np.isfinite(delta).all():
        return dict(status='unverified', reason='invalid_transform')
    # Bounded tolerance inside a component's search ROI, capped at 20 native px.
    sizes = []
    for part in config['parts']:
        q = np.asarray(part['quad_normalized'])*[width, height]
        sizes.append(float(np.linalg.norm(q-np.roll(q, -1, axis=0), axis=1).min()))
    limit = min(20., max(6., float(np.median(sizes))*.15))
    maximum = float(delta.max())
    return dict(status='aligned' if maximum <= limit else 'moved',
                max_displacement_px=maximum, displacement_limit_px=limit,
                matches=len(matches), inliers=int(keep.sum()),
                reference_to_current_half_size=matrix.tolist())
