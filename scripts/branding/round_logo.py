#!/usr/bin/env python3
"""Aegis Browser - create the curved-corner master icon asset from the master logo.

The master logo (aegis/branding/logo/aegis-logo.png) has sharp vertices that
look harsh as an app icon. This script derives a dedicated ICON asset:

  aegis/branding/logo/aegis-logo-rounded.png   (RGBA, mark on transparent)

Approach: trace the mark's outlines, then fillet every true polygon vertex
with a circular arc (radius scales with the mark, clamped by the adjacent
edges, so colinear diagonal-stroke points are left alone). Strokes are never
thinned or broken. The master sharp logo stays untouched (site/print logo);
icons are generated from the rounded asset by make_icons.py.
"""
import math
import os

import cv2
import numpy as np
from PIL import Image

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LOGO = os.path.join(REPO, "aegis", "branding", "logo", "aegis-logo.png")
OUT = os.path.join(REPO, "aegis", "branding", "logo", "aegis-logo-rounded.png")
MASTER_SIZE = 1600  # canonical icon-source resolution


def mark_alpha(img):
    """White-mark alpha from the master logo (same extraction as make_icons)."""
    rgb = np.asarray(img.convert("RGB"), dtype=np.float32)
    lum = rgb.mean(axis=2) / 255.0
    mask = lum > 0.5
    rows = np.where(mask.any(axis=1))[0]
    blocks, start = [], rows[0]
    for a, b in zip(rows, rows[1:]):
        if b - a >= 15:
            blocks.append((start, a))
            start = b
    blocks.append((start, rows[-1]))
    top, bot = max(blocks, key=lambda t: t[1] - t[0])
    sub = mask[top:bot + 1]
    cols = np.where(sub.any(axis=0))[0]
    left, right = cols[0], cols[-1] + 1
    alpha = lum[top:bot + 1, left:right + 1]
    alpha = np.clip((alpha - 0.35) / 0.35, 0.0, 1.0)
    return alpha


def fillet_poly(poly, radius):
    """Fillet each vertex of a closed polyline with a circular arc.

    poly: (N,2) float array, closed implicitly (last->first).
    Returns a new polyline with corners replaced by arc samples.
    """
    n = len(poly)
    out = []
    for i in range(n):
        A, B, C = poly[(i - 1) % n], poly[i], poly[(i + 1) % n]
        v1, v2 = A - B, C - B
        l1, l2 = np.linalg.norm(v1), np.linalg.norm(v2)
        if l1 < 1e-6 or l2 < 1e-6:
            continue
        u1, u2 = v1 / l1, v2 / l2
        cosang = float(np.clip(np.dot(u1, u2), -1.0, 1.0))
        ang = math.acos(cosang)                    # interior angle at B
        if ang > math.radians(160):                # near-straight: no fillet
            out.append(B)
            continue
        half = ang / 2.0
        t = radius / math.tan(half)                # tangent offset from vertex
        t = min(t, 0.30 * min(l1, l2))             # clamp by edge lengths
        r_eff = t * math.tan(half)
        if r_eff < 1.0:
            out.append(B)
            continue
        p1, p2 = B + u1 * t, B + u2 * t
        bis = u1 + u2
        nrm = np.linalg.norm(bis)
        if nrm < 1e-6:
            out.append(B)
            continue
        bis /= nrm
        center = B + bis * (r_eff / math.sin(half))
        a0 = math.atan2(p1[1] - center[1], p1[0] - center[0])
        a1 = math.atan2(p2[1] - center[1], p2[0] - center[0])
        # sweep the short way from a0 to a1
        da = (a1 - a0) % (2 * math.pi)
        if da > math.pi:
            da -= 2 * math.pi
        steps = max(3, int(math.ceil(abs(da) / math.radians(12))))
        for s in range(steps + 1):
            phi = a0 + da * s / steps
            out.append(center + np.array([math.cos(phi), math.sin(phi)]) * r_eff)
    return np.array(out)


def main():
    alpha = mark_alpha(Image.open(LOGO))
    h, w = alpha.shape
    print(f"mark: {w}x{h}")
    mask8 = (alpha >= 0.5).astype(np.uint8)

    contours, hier = cv2.findContours(mask8, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE)
    hier = hier[0]
    polys = []
    for i, c in enumerate(contours):
        if cv2.contourArea(c) < 40:
            continue
        eps = 0.0016 * cv2.arcLength(c, True)
        poly = cv2.approxPolyDP(c, eps, True).reshape(-1, 2).astype(np.float64)
        polys.append((hier[i][3], poly))  # (parent, poly); parent >=0 => hole
    print(f"contours kept: {len(polys)}")

    r_outer = 0.050 * max(h, w)   # big silhouette vertices (apex + base corners)
    r_hole = 0.028 * max(h, w)    # inner counters / small-triangle details
    print(f"fillet radius: outer {r_outer:.0f}px, holes {r_hole:.0f}px")

    filled = np.zeros((h, w), np.uint8)
    all_filleted = []
    for parent, poly in polys:
        d, p = 0, parent
        while p != -1:
            d += 1
            p = hier[p][3]
        f = fillet_poly(poly, r_hole if d % 2 else r_outer)
        all_filleted.append((parent, f))
    # paint depth-even white, depth-odd black (same rule as make_icons raster)
    def depth(parent):
        d, p = 0, parent
        while p != -1:
            d += 1
            p = hier[p][3]
        return d
    for parent, f in all_filleted:
        cv2.fillPoly(filled, [np.round(f).astype(np.int32)],
                     255 if depth(parent) % 2 == 0 else 0)

    # fidelity: filleted raster must stay close to the original mask
    inter = np.logical_and(filled > 0, mask8 > 0).sum()
    union = np.logical_or(filled > 0, mask8 > 0).sum()
    iou = inter / union if union else 0.0
    keep = (filled > 0).sum() / max(1, (mask8 > 0).sum())
    print(f"IoU vs original: {iou:.4f}, area kept: {keep:.3f}")
    assert iou > 0.90 and keep > 0.90, "fillet changed the mark too much"

    # smooth the raster edges (anti-aliasing only, no shape change)
    a = cv2.GaussianBlur(filled.astype(np.float32), (0, 0), 2.0)
    a = np.clip((a - 0.35) / 0.30, 0.0, 1.0)

    ys, xs = np.where(a > 0.02)
    a = a[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    ah, aw = a.shape
    side = max(ah, aw)
    canvas = np.zeros((side, side), np.float32)
    oy, ox = (side - ah) // 2, (side - aw) // 2
    canvas[oy:oy + ah, ox:ox + aw] = a
    canvas = cv2.resize(canvas, (MASTER_SIZE, MASTER_SIZE), interpolation=cv2.INTER_AREA)

    rgba = np.zeros((MASTER_SIZE, MASTER_SIZE, 4), np.uint8)
    rgba[:, :, 0] = 255
    rgba[:, :, 1] = 255
    rgba[:, :, 2] = 255
    rgba[:, :, 3] = (canvas * 255).astype(np.uint8)
    Image.fromarray(rgba).save(OUT)
    print("OK ->", OUT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
