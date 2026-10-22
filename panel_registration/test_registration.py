import copy
import json
from pathlib import Path
import sys
import unittest
import tempfile
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from panel import read_panel, calibrate, transform, ANCHORS


class RegistrationTests(unittest.TestCase):
    def setUp(self):
        self.panel = read_panel()
        self.capture = dict(width=1920, height=1440, request_id='test', camera_id='0', scene_signature=[0]*768, image_timestamp_ns=123)
        self.h = np.array([[8.1, 1.3, 140], [-.8, -7.2, 1190], [.001, -.0008, 1.]])
        points = {p['designator']: p for p in self.panel['parts']}
        self.anchors = [dict(designator=k, **dict(zip(('x', 'y'), transform(self.h, [[points[k]['x_mm'], points[k]['y_mm']]])[0]))) for k in ANCHORS]

    def test_actual_workbook(self):
        self.assertEqual(self.panel['count'], 20)
        self.assertEqual((self.panel['columns'], self.panel['rows']), (4, 5))
        self.assertEqual({p['rotation_deg'] for p in self.panel['parts']}, {0})

    def test_twenty_centers_and_roi(self):
        registration = calibrate(self.panel, self.capture, self.anchors)
        expected = transform(self.h, [[p['x_mm'], p['y_mm']] for p in self.panel['parts']])
        observed = np.array([p['center_px'] for p in registration['parts']])
        np.testing.assert_allclose(observed, expected, atol=.001)
        self.assertEqual(len(registration['parts']), 20)
        self.assertEqual(np.shape(registration['parts'][0]['quad_normalized']), (4, 2))

    def test_degenerate_and_bad_points_rejected(self):
        for mode in ('duplicate', 'outside', 'nonfinite', 'collinear', 'crossed'):
            anchors = copy.deepcopy(self.anchors)
            if mode == 'duplicate': anchors[1]['designator'] = anchors[0]['designator']
            if mode == 'outside': anchors[0]['x'] = -10
            if mode == 'nonfinite': anchors[0]['x'] = float('nan')
            if mode == 'collinear':
                for i,a in enumerate(anchors): a.update(x=100+i*30, y=200)
            if mode == 'crossed':
                anchors[1]['x'],anchors[2]['x']=anchors[2]['x'],anchors[1]['x']
                anchors[1]['y'],anchors[2]['y']=anchors[2]['y'],anchors[1]['y']
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                calibrate(self.panel, self.capture, anchors)

    def test_roi_bounds(self):
        with self.assertRaises(ValueError): calibrate(self.panel, self.capture, self.anchors, 0, 12)

    def test_new_registration_preserves_active_reading_and_selected_part(self):
        from server import Application
        with tempfile.TemporaryDirectory() as temp:
            app=Application('test',temp)
            app.capture=self.capture
            app.registration=calibrate(self.panel,self.capture,self.anchors)
            app.bridge.command=lambda *args,**kwargs:{'ok':True}
            app.post('/api/select',{'designator':'CORE1_10'})
            restarted=Application('test',temp)
            restarted.capture=self.capture
            restarted.bridge.command=lambda *args,**kwargs:{'ok':True}
            registration=restarted.post('/api/register',dict(capture_id='test',anchors=self.anchors))['registration']
            self.assertFalse(registration['body_outline_only'])
            self.assertEqual([p['designator'] for p in registration['parts']],['CORE1_10'])
            self.assertEqual(len(registration['all_parts']),20)

    def test_web_apply_enables_loop_and_start_restores_all_parts(self):
        from server import Application
        with tempfile.TemporaryDirectory() as temp:
            app=Application('test',temp)
            app.settings={'reading_enabled':True}
            reg=calibrate(self.panel,self.capture,self.anchors)
            reg.update(calibration_id='id',body_outline_only=False)
            reg['all_parts']=reg['parts'];reg['parts']=reg['parts'][:1]
            app.registration=reg
            sent=[]
            app.bridge.command=lambda operation,payload:sent.append(copy.deepcopy(payload)) or {'ok':True}
            app.post('/api/apply',dict(calibration_id='id',capture_id=reg['capture_id']))
            self.assertTrue(sent[-1]['auto_cycle'])
            self.assertTrue(sent[-1]['fresh_geometry_per_attempt'])
            self.assertEqual(len(sent[-1]['parts']),20)
            app.post('/api/select',dict(designator='CORE1_10'))
            self.assertFalse(sent[-1]['auto_cycle'])
            self.assertEqual(len(sent[-1]['parts']),1)
            app.post('/api/start',dict(calibration_id=reg['calibration_id'],capture_id=reg['capture_id']))
            self.assertTrue(sent[-1]['auto_cycle'])
            self.assertEqual(len(sent[-1]['parts']),20)

    def test_read_values_are_exact_persisted_and_capture_scoped(self):
        from server import Application
        with tempfile.TemporaryDirectory() as temp:
            app=Application('test',temp);app.capture=self.capture
            app.registration=calibrate(self.panel,self.capture,self.anchors)
            app.bridge.status=lambda:dict(connected=True,app=dict(results_capture_id='test',read_results={'CORE1':'00123','CORE1_1':'line one\nline two','UNKNOWN':'invalid'}))
            result=app.device()['results']
            self.assertEqual(result['values'],{'CORE1':'00123','CORE1_1':'line one\nline two'})
            app.bridge.status=lambda:dict(connected=False)
            self.assertEqual(app.device()['results'],result)
            restarted=Application('test',temp);restarted.capture=self.capture
            restarted.bridge.status=lambda:dict(connected=False)
            self.assertEqual(restarted.device()['results'],result)
            restarted.capture=dict(self.capture,request_id='new-capture')
            self.assertEqual(restarted.device()['results']['values'],{})
            restarted.bridge.status=lambda:dict(connected=True,app=dict(results_capture_id='test',read_results={'CORE1':'old'}))
            self.assertEqual(restarted.device()['results']['values'],{})

    def test_core_extent_with_two_mm_margin(self):
        registration = calibrate(self.panel, self.capture, self.anchors)
        self.assertEqual((registration['roi_width_mm'], registration['roi_height_mm']), (15, 9))
        inverse = np.linalg.inv(np.asarray(registration['homography_mm_to_native_px']))
        for source, part in zip(self.panel['parts'], registration['parts']):
            corners = transform(inverse, np.asarray(part['quad_normalized'])*[1920,1440])
            expected = np.array([[-7.5,-4.5],[7.5,-4.5],[7.5,4.5],[-7.5,4.5]])
            np.testing.assert_allclose(corners-[source['x_mm'],source['y_mm']], expected, atol=.001)

    def test_registered_localization_is_local_and_infers_no_fixed_grid(self):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'android_capture/python'))
        import panel_registration as engine
        config = calibrate(self.panel, self.capture, self.anchors)
        image = bytes(1920*1440)
        result = json.loads(engine.analyze(image, 1920, 1440, json.dumps(config), 'null'))
        self.assertEqual(result['registered_component_count'], 20)
        self.assertEqual(result['component_index'], 0)
        self.assertEqual(result['symbols'], [])
        target = np.array(config['parts'][7]['quad_normalized']).ravel().tolist()
        result = json.loads(engine.analyze(image, 1920, 1440, json.dumps(config), json.dumps(target)))
        self.assertEqual(result['component_index'], 7)

    def test_reviewed_calibration_id_is_required_for_apply(self):
        from server import Application
        with tempfile.TemporaryDirectory() as temp:
            app = Application('test', temp)
            app.capture = self.capture
            calls = []
            app.bridge.command = lambda operation, payload=None: calls.append((operation, payload)) or {'ok': True}
            result = app.post('/api/register', dict(capture_id='test', anchors=self.anchors))
            registration = result['registration']
            with self.assertRaises(ValueError):
                app.post('/api/apply', dict(capture_id='test', calibration_id='stale'))
            self.assertFalse(calls)
            app.post('/api/apply', dict(capture_id='test', calibration_id=registration['calibration_id']))
            self.assertEqual(calls[0][0], 'apply')
            self.assertEqual(len(calls[0][1]['parts']), 20)

    def test_new_capture_identity_is_required_for_registration(self):
        from server import Application
        with tempfile.TemporaryDirectory() as temp:
            app = Application('test', temp)
            app.capture = self.capture
            with self.assertRaises(ValueError):
                app.post('/api/register', dict(capture_id='old-photo', anchors=self.anchors))


if __name__ == '__main__':
    unittest.main()
