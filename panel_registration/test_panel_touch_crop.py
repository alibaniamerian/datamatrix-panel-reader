import importlib.util
import json
from pathlib import Path
import sys
import time
import unittest
from unittest.mock import patch
import numpy as np

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'android_capture/python'))
spec=importlib.util.spec_from_file_location('panel_engine',Path(__file__).resolve().parents[1]/'android_capture/python/panel_registration.py')
engine=importlib.util.module_from_spec(spec);spec.loader.exec_module(engine)


class PanelTouchCropTests(unittest.TestCase):
    def test_known_small_yuv_body_does_not_repeat_part_detection(self):
        with patch.object(engine.lb.eg,'find_parts',side_effect=AssertionError('body already supplied')):
            result=engine.lb.locate_gray(np.zeros((55,125),np.float32),source_width=1920,known_body=True)
        self.assertEqual(result['part_count'],1)
        self.assertNotEqual(result.get('reason'),'invalid_frame')

    def test_confirmed_body_replaces_coarse_search_region(self):
        body=[[.4,.4],[.6,.4],[.6,.6],[.4,.6]]
        part=dict(designator='CORE1',quad_normalized=[[.2,.2],[.8,.2],[.8,.8],[.2,.8]],body_quad_normalized=body)
        with patch.object(engine.lb,'locate_gray',return_value={'status':'no_boundary','localizations':[]}) as detector:
            result=json.loads(engine.locate(np.zeros((1000,1000),np.float32),dict(parts=[part]),'null',time.perf_counter(),{'status':'aligned'}))
        self.assertEqual(result['search_roi_normalized'],[.4,.4,.6,.6])
        self.assertTrue(detector.call_args.kwargs['known_body'])

    def test_each_of_twenty_parts_uses_only_its_crop_and_original_scale(self):
        parts=[]
        for row in range(5):
            for col in range(4):
                x,y=.1+col*.25,.1+row*.18
                parts.append(dict(designator='CORE'+str(len(parts)),quad_normalized=[[x-.04,y-.03],[x+.04,y-.03],[x+.04,y+.03],[x-.04,y+.03]]))
        config=dict(parts=parts)
        gray=np.arange(1000*800,dtype=np.float32).reshape(1000,800)
        for index,part in enumerate(parts):
            target=np.asarray(part['quad_normalized']).ravel().tolist()
            with patch.object(engine.lb,'locate_gray',return_value={'status':'no_boundary','localizations':[]}) as detector:
                result=json.loads(engine.locate(gray,config,json.dumps(target),time.perf_counter(),{'status':'aligned'}))
                self.assertEqual(result['component_index'],index)
                self.assertEqual(result['localizer'],'single_code_touch_crop')
                self.assertEqual(result['search_mode'],'touch_only')
                crop=detector.call_args.args[0]
                self.assertLess(crop.size,gray.size*.02)
                x0,y0,x1,y1=result['search_roi_px']
                np.testing.assert_array_equal(crop,gray[y0:y1,x0:x1])
                self.assertEqual(detector.call_args.kwargs['source_width'],800)


if __name__=='__main__':unittest.main()
