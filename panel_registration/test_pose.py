"""Optional integration check using captured images; never writes a phone calibration."""
import argparse
import json
from pathlib import Path
import sys
import cv2
import numpy as np

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'android_capture/python'))
import panel_pose

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--reference',required=True)
    parser.add_argument('--current',required=True)
    parser.add_argument('--calibration',required=True)
    args=parser.parse_args()
    config=json.loads(Path(args.calibration).read_text(encoding='utf-8'))
    config['reference_path']=str(Path(args.reference).resolve())
    image=cv2.imread(args.current,cv2.IMREAD_GRAYSCALE)
    assert image is not None
    results={}
    for name,frame,expected in [('current',image,'aligned'),('shift35',cv2.warpAffine(image,np.float32([[1,0,35],[0,1,0]]),(image.shape[1],image.shape[0])),'moved'),('blank',np.zeros_like(image),'unverified')]:
        results[name]=panel_pose.check(frame,config)
        if expected == 'moved':
            assert results[name]['status']=='unverified'
            assert panel_pose.check(frame,config)['status']=='unverified'
            results[name]=panel_pose.check(frame,config)
        assert results[name]['status']==expected,(name,results[name])
    shifted=cv2.warpAffine(image,np.float32([[1,0,35],[0,1,0]]),(image.shape[1],image.shape[0]))
    assert panel_pose.check(shifted,config)['status']=='unverified'
    assert panel_pose.check(image,config)['status']=='aligned'
    assert panel_pose.check(shifted,config)['consecutive_moved_frames']==1
    print(json.dumps(results,indent=2))
