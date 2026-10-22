"""Read placement values only; workbook text is data, never executable input."""
from pathlib import Path
import hashlib
import math
import posixpath
import xml.etree.ElementTree as ET
from zipfile import ZipFile

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
WORKBOOK = ROOT / 'data/ferrite_positions.xlsx'
if not WORKBOOK.exists():
    WORKBOOK = ROOT / 'examples/panel.example.xlsx'
NS = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
ANCHORS = ['CORE1', 'CORE1_3', 'CORE1_19', 'CORE1_16']


def read_panel(path=WORKBOOK):
    path = Path(path)
    with ZipFile(path) as archive:
        if sum(i.file_size for i in archive.infolist()) > 32_000_000:
            raise ValueError('Workbook is too large')
        strings = []
        if 'xl/sharedStrings.xml' in archive.namelist():
            strings = [''.join(si.itertext()) for si in ET.fromstring(archive.read('xl/sharedStrings.xml')).findall('s:si', NS)]
        relations = {r.attrib['Id']: r.attrib['Target'] for r in ET.fromstring(archive.read('xl/_rels/workbook.xml.rels'))}
        workbook = ET.fromstring(archive.read('xl/workbook.xml'))
        found = []
        for sheet in workbook.findall('s:sheets/s:sheet', NS):
            target = relations[sheet.attrib['{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id']]
            target = target.lstrip('/') if target.startswith('/') else posixpath.normpath('xl/' + target)
            if not target.startswith('xl/'):
                raise ValueError('Invalid worksheet path')
            headers = None
            for row in ET.fromstring(archive.read(target)).findall('s:sheetData/s:row', NS):
                values = {}
                for cell in row.findall('s:c', NS):
                    column = ''.join(c for c in cell.attrib['r'] if c.isalpha())
                    v = cell.find('s:v', NS)
                    value = v.text if v is not None else ''
                    if cell.attrib.get('t') == 's':
                        value = strings[int(value)]
                    elif cell.attrib.get('t') == 'inlineStr':
                        value = ''.join(cell.find('s:is', NS).itertext())
                    values[column] = value
                if headers is None:
                    headers = {v: k for k, v in values.items()}
                    if not {'Designator', 'Center-X(mm)', 'Center-Y(mm)'}.issubset(headers):
                        break
                    continue
                name = values.get(headers['Designator'], '')
                if not (name == 'CORE1' or (name.startswith('CORE1_') and name[6:].isdigit())):
                    continue
                item = dict(designator=name, x_mm=float(values[headers['Center-X(mm)']]),
                            y_mm=float(values[headers['Center-Y(mm)']]),
                            rotation_deg=float(values.get(headers.get('Rotation'), 0)),
                            layer=values.get(headers.get('Layer'), ''),
                            footprint=values.get(headers.get('Footprint'), ''),
                            sheet=sheet.attrib['name'], row=int(row.attrib['r']))
                if not all(math.isfinite(item[k]) for k in ('x_mm', 'y_mm', 'rotation_deg')):
                    raise ValueError('Non-finite placement coordinate')
                found.append(item)
    if len({p['designator'] for p in found}) != len(found) or not found:
        raise ValueError('Missing or duplicate CORE1 designators')
    if not set(ANCHORS).issubset(p['designator'] for p in found):
        raise ValueError('Four reference parts are missing')
    found.sort(key=lambda p: (-p['y_mm'], p['x_mm']))
    return dict(name='20W Panel V2.4', source_file=path.name,
                source_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                units='mm', parts=found, anchor_ids=ANCHORS,
                count=len(found), columns=len({p['x_mm'] for p in found}),
                rows=len({p['y_mm'] for p in found}))


def transform(h, points):
    points = np.asarray(points, np.float64)
    v = np.column_stack((points, np.ones(len(points)))) @ h.T
    if (np.abs(v[:, 2]) < 1e-9).any():
        raise ValueError('Projection crosses infinity')
    out = v[:, :2] / v[:, 2:]
    if not np.isfinite(out).all():
        raise ValueError('Invalid projected coordinate')
    return out


def calibrate(panel, capture, anchors, roi_width_mm=15, roi_height_mm=9):
    if len(anchors) != 4 or {a.get('designator') for a in anchors} != set(panel['anchor_ids']):
        raise ValueError('Select each of the four reference parts once')
    w, h = capture['width'], capture['height']
    by_id = {p['designator']: p for p in panel['parts']}
    clicked = {a['designator']: [float(a['x']), float(a['y'])] for a in anchors}
    destination = np.asarray([clicked[k] for k in panel['anchor_ids']], np.float64)
    if not np.isfinite(destination).all() or (destination < 0).any() or (destination > [w, h]).any():
        raise ValueError('Reference point is outside the original image')
    polygon = destination.astype(np.float32)
    if not cv2.isContourConvex(polygon) or abs(cv2.contourArea(polygon)) < .01 * w * h:
        raise ValueError('Reference points must surround the panel without crossing')
    rw, rh = float(roi_width_mm), float(roi_height_mm)
    if not (1 <= rw <= 25 and 1 <= rh <= 25):
        raise ValueError('Search region must be between 1 and 25 mm')
    source = np.asarray([[by_id[k]['x_mm'], by_id[k]['y_mm']] for k in panel['anchor_ids']], np.float32)
    matrix = cv2.getPerspectiveTransform(source, polygon)
    if abs(np.linalg.det(matrix)) < 1e-12:
        raise ValueError('Degenerate registration')
    parts = []
    for part in panel['parts']:
        x, y = part['x_mm'], part['y_mm']
        center = transform(matrix, [[x, y]])[0]
        if (center < 0).any() or (center > [w, h]).any():
            raise ValueError('A projected center is outside the image; check the four clicks')
        angle = np.radians(part['rotation_deg'])
        rotation = np.array([[np.cos(angle), -np.sin(angle)], [np.sin(angle), np.cos(angle)]])
        corners = np.array([[-rw/2, -rh/2], [rw/2, -rh/2], [rw/2, rh/2], [-rw/2, rh/2]]) @ rotation.T + [x, y]
        quad = transform(matrix, corners)
        parts.append(dict(designator=part['designator'], center_px=center.tolist(),
                          center_normalized=(center/[w, h]).tolist(),
                          quad_normalized=(quad/[w, h]).tolist()))
    return dict(protocol_version=1, manual_alignment=True, capture_id=capture['request_id'],
                width=w, height=h, camera_id=capture['camera_id'], source='YUV',
                source_signature=capture['scene_signature'], image_timestamp_ns=capture['image_timestamp_ns'],
                homography_mm_to_native_px=matrix.tolist(), anchors=anchors,
                roi_width_mm=rw, roi_height_mm=rh, parts=parts,
                source_sha256=panel['source_sha256'])
