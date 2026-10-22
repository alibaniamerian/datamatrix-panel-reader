import json
from pathlib import Path
import sys
import unittest
import numpy as np

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'python'))
import touch_roi


class TouchRoiTests(unittest.TestCase):
    def test_crop_excludes_distractor_and_remaps_both_resolutions(self):
        for width,height in [(1920,1440),(4080,3060)]:
            image=np.zeros((height,width),np.float32)
            image[:height//4,:width//4]=99999.25
            region=[.25,.25,.75,.75]
            def locate(crop,source_width):
                self.assertEqual(source_width,width)
                self.assertEqual(crop.max(),0)
                return dict(quad_normalized=[[0,0],[1,0],[1,1],[0,1]],localizations=[dict(quad_normalized=[[0,0],[1,0],[1,1],[0,1]])])
            result=touch_roi.locate(image,json.dumps(region),locate)
            expected=[[.25,.25],[.75,.25],[.75,.75],[.25,.75]]
            np.testing.assert_allclose(result['quad_normalized'],expected)
            np.testing.assert_allclose(result['localizations'][0]['quad_normalized'],expected)

    def test_raw_linear_values_are_not_changed(self):
        image=np.full((200,300),-23.875,np.float32);image[100,150]=70000.125
        def locate(crop,source_width):
            self.assertEqual(crop.dtype,np.float32)
            self.assertEqual(crop.min(),-23.875)
            self.assertEqual(crop.max(),70000.125)
            return dict(status='no_boundary')
        touch_roi.locate(image,'[0.25,0.25,0.75,0.75]',locate)

    def test_no_touch_preserves_whole_frame_route(self):
        image=np.zeros((100,200),np.float32)
        def locate(gray):
            self.assertIs(gray,image)
            return {'status':'no_boundary'}
        self.assertEqual(touch_roi.locate(image,'null',locate),{'status':'no_boundary'})

    def test_invalid_touch_never_falls_back_to_whole_frame(self):
        for region in ['[0,0,2,1]','[0.5,0,0.4,1]','[0,0,1]']:
            with self.assertRaises(ValueError):
                touch_roi.locate(np.zeros((100,100)),region,lambda *args,**kwargs:self.fail('must not search'))


if __name__=='__main__':unittest.main()
