import unittest
import numpy as np
import cv2
from body_outline import fit


class BodyOutlineTests(unittest.TestCase):
    def test_shifted_body_edges_with_either_polarity(self):
        quad=[[0,0],[299,0],[299,179],[0,179]]
        for background,body in [(30,210),(210,30)]:
            image=np.full((180,300),background,np.uint8)
            cv2.rectangle(image,(38,45),(258,145),body,-1)
            result=fit(image,quad)
            self.assertTrue(result['detected'],result)
            np.testing.assert_allclose(np.mean(result['quad_px'],axis=0),[148,95],atol=2)
            # cv2.rectangle fills both endpoint pixels: outer transitions span 221.
            self.assertAlmostEqual(np.linalg.norm(np.asarray(result['quad_px'])[1]-result['quad_px'][0]),221,delta=1)

    def test_blank_is_only_estimate(self):
        result=fit(np.full((180,300),100,np.uint8),[[0,0],[299,0],[299,179],[0,179]])
        self.assertFalse(result['detected'])

    def test_registered_center_is_preserved_when_no_edges(self):
        center=[155.,87.]
        result=fit(np.full((180,300),100,np.uint8),[[0,0],[299,0],[299,179],[0,179]],center_px=center)
        np.testing.assert_allclose(np.mean(result['quad_px'],axis=0),center,atol=.0001)
        self.assertEqual(result['shift_mm'],[0.,0.])
        self.assertAlmostEqual(np.linalg.norm(np.asarray(result['quad_px'])[1]-result['quad_px'][0])*15/299,11,places=4)
        self.assertAlmostEqual(np.linalg.norm(np.asarray(result['quad_px'])[2]-result['quad_px'][1])*9/179,5,places=4)

    def test_strong_offset_edges_cannot_pull_center_beyond_half_mm(self):
        image=np.full((180,300),20,np.uint8)
        cv2.rectangle(image,(65,40),(285,140),220,-1)
        result=fit(image,[[0,0],[299,0],[299,179],[0,179]])
        self.assertLessEqual(np.linalg.norm(result['shift_mm']),.5+1e-9)

    def test_rotated_body_with_print_and_uneven_illumination(self):
        image=np.full((180,300),35,np.float32)
        box=cv2.boxPoints(((151,92),(222,103),3))
        cv2.fillConvexPoly(image,np.int32(box),160)
        rng=np.random.default_rng(41)
        for y in range(65,110,5):
            for x in range(115,160,5):
                if rng.random()>.5:cv2.rectangle(image,(x,y),(x+3,y+3),70,-1)
        image+=np.linspace(0,45,300,dtype=np.float32)[None,:]
        result=fit(image,[[0,0],[299,0],[299,179],[0,179]])
        self.assertTrue(result['detected'],result)
        self.assertAlmostEqual(result['angle_deg'],3,delta=1)
        np.testing.assert_allclose(np.mean(result['quad_px'],axis=0),[151,92],atol=2)

    def test_single_strong_edge_does_not_make_a_body(self):
        image=np.full((180,300),30,np.uint8)
        image[:,258:]=220
        self.assertFalse(fit(image,[[0,0],[299,0],[299,179],[0,179]])['detected'])


if __name__=='__main__':unittest.main()
