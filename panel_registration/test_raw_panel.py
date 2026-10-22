"""RAW registration keeps linear samples and validates reference geometry first."""
import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import numpy as np

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'android_capture/python'))
spec=importlib.util.spec_from_file_location('panel_engine',Path(__file__).resolve().parents[1]/'android_capture/python/panel_registration.py')
engine=importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)


class RawPanelTests(unittest.TestCase):
    def test_manual_yuv_does_not_measure_pose(self):
        config=dict(width=80,height=60,source='YUV',manual_alignment=True,require_pose_check=True)
        with patch.object(engine.panel_pose,'check',side_effect=AssertionError('motion guard disabled')),patch.object(engine,'locate',return_value='reading'):
            self.assertEqual(engine.analyze(bytes(80*60),80,60,json.dumps(config),'null'),'reading')

    def test_manual_alignment_does_not_gate_or_measure_pose(self):
        state={'meta':{'crop':[0,0,160,120]}}
        config=dict(width=80,height=60,parts=[{}],capture_id='manual',manual_alignment=True)
        with patch('raw_analysis.plane',return_value=np.ones((120,160),np.float32)), patch.object(engine.panel_pose,'check',side_effect=AssertionError('motion guard disabled')), patch.object(engine,'locate',return_value='reading') as locate:
            for channel in ['FULL','R','G','B']:
                self.assertEqual(engine.analyze_raw(state,channel,json.dumps(config),'null'),'reading')
            self.assertEqual(locate.call_count,4)

    def test_linear_channels_and_normalized_geometry(self):
        config=dict(width=80,height=60,parts=[dict(center_normalized=[.4,.6])])
        planes={name:np.arange(120*160,dtype=np.float32).reshape(120,160)*3.37-123.4+i for i,name in enumerate(['FULL','R','G','B'])}
        for channel in planes:
            with self.subTest(channel=channel), patch('raw_analysis.plane',side_effect=lambda state,name:planes[name]), patch.object(engine.panel_pose,'check',return_value={'status':'aligned'}), patch.object(engine,'locate',return_value='ok') as locate:
                self.assertEqual(engine.analyze_raw({'meta':{'crop':[0,0,160,120]}},channel,json.dumps(config),'null'),'ok')
                args=locate.call_args.args
                self.assertIs(args[0],planes[channel])
                self.assertEqual((args[1]['width'],args[1]['height']),(160,120))
                self.assertEqual(args[1]['parts'],config['parts'])

    def test_unverified_raw_geometry_never_localizes(self):
        config=dict(width=80,height=60,parts=[{}])
        with patch('raw_analysis.plane',return_value=np.ones((120,160),np.float32)), patch.object(engine.panel_pose,'check',return_value={'status':'unverified'}), patch.object(engine,'locate') as locate:
            result=json.loads(engine.analyze_raw({'meta':{'crop':[0,0,160,120]}},'R',json.dumps(config),'null'))
            self.assertEqual(result['symbols'],[])
            locate.assert_not_called()

    def test_channels_share_one_frame_motion_check(self):
        state={'meta':{'crop':[0,0,160,120]}}
        config=dict(width=80,height=60,parts=[{}],capture_id='same_frame')
        with patch('raw_analysis.plane',return_value=np.ones((120,160),np.float32)), patch.object(engine.panel_pose,'check',return_value={'status':'unverified'}) as check:
            for channel in ['FULL','R','G','B']:
                engine.analyze_raw(state,channel,json.dumps(config),'null')
            self.assertEqual(check.call_count,1)


if __name__=='__main__':unittest.main()
