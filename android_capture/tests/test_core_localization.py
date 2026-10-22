import importlib.util
import json
from pathlib import Path
import sys
import time
import unittest
from unittest.mock import patch

import cv2
import numpy as np

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'android_capture/python'))
import core_localization as core
import engraved_geometry as eg


class CoreLocalizationTests(unittest.TestCase):
    def test_ferrite_edge_bound_includes_tilted_endpoints(self):
        for initial in (np.array([[0,0],[220,0],[220,120],[0,120]],float),
                        np.array([[10,10],[240,30],[220,150],[0,130]],float)):
            candidate=initial+np.array([[-40,-50],[70,-20],[50,60],[-60,30]])
            bounded,fractions,limits=core.bound_body_corners(initial,candidate)
            self.assertLessEqual(max(fractions),.05+1e-12)
            for i in range(4):
                side=initial[(i+1)%4]-initial[i];n=np.array([-side[1],side[0]])/np.linalg.norm(side)
                drift=np.abs((bounded[[i,(i+1)%4]]-initial[i])@n)
                self.assertLessEqual(float(drift.max()),limits[i]+1e-9)
            # Repeated correction remains anchored to INITIAL, never drifts onward.
            again,shifts,_=core.bound_body_corners(initial,bounded+(candidate-initial))
            self.assertLessEqual(max(shifts),.05+1e-12)

    def test_body_legal_fit_is_unchanged(self):
        initial=np.array([[0,0],[220,0],[220,120],[0,120]],float)
        legal=initial+[2.,3.]
        bounded,_,_=core.bound_body_corners(initial,legal)
        np.testing.assert_allclose(bounded,legal)

    def test_search_starts_at_exact_center_and_expands_outward(self):
        points=core.center_out_positions(0,0,220,120,73,110,60,2.,3.)
        np.testing.assert_allclose(points[0],[110-73/2,60-73/2])
        radius=np.sum((points-points[0])**2,axis=1)
        self.assertTrue(np.all(np.diff(radius)>=-1e-8))
        self.assertLessEqual(points[:,0].min(),0)
        self.assertGreaterEqual(points[:,0].max(),220-73)

    def test_off_center_code_both_polarities_without_any_grid(self):
        for ink in (60.,230.):
            image=np.full((200,300),25,np.float32)
            cv2.rectangle(image,(40,40),(260,160),150,-1)
            rng=np.random.default_rng(21)
            for y in range(10):
                for x in range(10):
                    if rng.random()>.5:
                        cv2.rectangle(image,(167+x*7,65+y*7),(172+x*7,70+y*7),ink,-1)
            with patch.object(eg,'sample_board',side_effect=AssertionError('localizer must not sample a grid')),patch.object(eg,'finder_conformance',side_effect=AssertionError('no grid')):
                result=core.locate(image,np.diag([20.,20.,1.]),[[40,40],[260,40],[260,160],[40,160]])
            self.assertEqual(result['status'],'boundary_candidate')
            np.testing.assert_allclose(result['code_center_px'],[202,100],atol=8)
            self.assertGreater(abs(result['offset_in_body_mm'][0]),2.)
            self.assertNotIn('grid',result)

    def test_blank_body_has_no_code(self):
        image=np.full((200,300),20,np.float32)
        cv2.rectangle(image,(40,40),(260,160),150,-1)
        result=core.locate(image,np.diag([20.,20.,1.]),[[40,40],[260,40],[260,160],[40,160]])
        self.assertEqual(result['status'],'no_boundary')
        self.assertEqual(result['localizations'],[])

    def test_large_raw_wall_sampling_preserves_coordinates(self):
        image=np.arange(400,dtype=np.float32).reshape(20,20)
        x=np.tile(np.arange(20,dtype=np.float32),2000)
        y=np.full(len(x),7,np.float32)
        np.testing.assert_array_equal(eg.bilinear(image,x,y),image[7,x.astype(int)])

    def test_raw_resolution_scales_homography_and_outputs(self):
        spec=importlib.util.spec_from_file_location('panel_engine_test',ROOT/'android_capture/python/panel_registration.py')
        panel=importlib.util.module_from_spec(spec);spec.loader.exec_module(panel)
        part=dict(designator='CORE1',quad_normalized=[[.1,.1],[.9,.1],[.9,.9],[.1,.9]],body_quad_normalized=[[.2,.2],[.8,.2],[.8,.8],[.2,.8]])
        config=dict(parts=[part],homography_mm_to_native_px=np.eye(3).tolist(),_reference_width=80,_reference_height=60)
        response=dict(status='boundary_candidate',body_status='ok',body_quad_px=[[32,24],[128,24],[128,96],[32,96]],quad_px=[[40,30],[80,30],[80,70],[40,70]],localizations=[])
        with patch.object(core,'locate',return_value=response) as locate:
            result=json.loads(panel.locate(np.zeros((120,160),np.float32),config,'null',time.perf_counter(),{}))
        np.testing.assert_allclose(locate.call_args.args[1],np.diag([2.,2.,1.]))
        np.testing.assert_allclose(result['quad_normalized'][0],[.25,.25])

    def test_loop_reuses_geometry_but_still_accepts_new_frame_dimensions(self):
        spec=importlib.util.spec_from_file_location('panel_cache_test',ROOT/'android_capture/python/panel_registration.py')
        panel=importlib.util.module_from_spec(spec);spec.loader.exec_module(panel)
        part=dict(designator='CORE1',quad_normalized=[[.1,.1],[.9,.1],[.9,.9],[.1,.9]],body_quad_normalized=[[.2,.2],[.8,.2],[.8,.8],[.2,.8]])
        config=dict(parts=[part],homography_mm_to_native_px=np.eye(3).tolist(),auto_cycle=True,capture_id='a',calibration_id='b',fresh_geometry_per_attempt=False)
        response=dict(status='boundary_candidate',window_z=4,body_status='ok',body_quad_px=[[20,20],[80,20],[80,80],[20,80]],quad_px=[[30,30],[70,30],[70,70],[30,70]],localizations=[])
        with patch.object(core,'locate',side_effect=lambda *args:json.loads(json.dumps(response))) as locate,patch.object(panel.time,'monotonic',side_effect=[0.,0.,9.,10.1,10.1]):
            first=json.loads(panel.locate(np.zeros((100,100),np.float32),config,'null',time.perf_counter(),{}))
            second=json.loads(panel.locate(np.zeros((200,200),np.float32),config,'null',time.perf_counter(),{}))
            third=json.loads(panel.locate(np.zeros((100,100),np.float32),config,'null',time.perf_counter(),{}))
        self.assertEqual(locate.call_count,2)
        self.assertTrue(second['geometry_reused'])
        self.assertNotIn('geometry_reused',third)
        self.assertEqual(third['geometry_age_seconds'],0.)
        np.testing.assert_allclose(first['quad_normalized'],second['quad_normalized'])
        np.testing.assert_allclose(second['code_center_px'],[100,100])

    def test_default_attempt_relocalizes_body_and_code_from_new_pixels(self):
        spec=importlib.util.spec_from_file_location('panel_fresh_test',ROOT/'android_capture/python/panel_registration.py')
        panel=importlib.util.module_from_spec(spec);spec.loader.exec_module(panel)
        part=dict(designator='CORE1',quad_normalized=[[.1,.1],[.9,.1],[.9,.9],[.1,.9]],body_quad_normalized=[[.2,.2],[.8,.2],[.8,.8],[.2,.8]])
        config=dict(parts=[part],homography_mm_to_native_px=np.eye(3).tolist(),capture_id='fresh',calibration_id='fresh',auto_cycle=True)
        response=dict(status='boundary_candidate',window_z=4,body_status='ok',quad_px=[[30,30],[70,30],[70,70],[30,70]],localizations=[])
        with patch.object(core,'locate',side_effect=lambda *args:json.loads(json.dumps(response))) as locate:
            first=json.loads(panel.locate(np.zeros((100,100),np.float32),config,'null',time.perf_counter(),{}))
            second=json.loads(panel.locate(np.ones((100,100),np.float32),config,'null',time.perf_counter(),{}))
        self.assertEqual(locate.call_count,2)
        self.assertTrue(np.all(locate.call_args.args[0]==1))
        self.assertTrue(second['fresh_geometry_per_attempt'])
        self.assertNotIn('geometry_reused',first)
        self.assertNotIn('geometry_reused',second)


if __name__=='__main__':unittest.main()
