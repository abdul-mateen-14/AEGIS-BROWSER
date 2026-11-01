#!/usr/bin/env python3
"""Aegis Browser - generate the full Android launcher icon set from the master logo.

Inputs : aegis/branding/logo/aegis-logo.png   (master mark, monochrome)
Outputs: aegis/branding/icons/res_chromium_base/
         mipmap-{mdpi..xxxhdpi}/app_icon.png                  legacy launcher (48..192)
         mipmap-{mdpi..xxxhdpi}/layered_app_icon.png          composed adaptive fg (108..432)
         mipmap-{mdpi..xxxhdpi}/layered_app_icon_background.png  bg layer (108..432)
         mipmap-nodpi/layered_app_icon_foreground.xml         traced VectorDrawable (108dp)
         drawable/themed_app_icon.xml                         monochrome vector (90dp)
         preview.png                                          human check

The mark is traced from the master PNG into vector path data, so the icon is
resolution independent and pixel-faithful to the supplied logo.
"""
import os
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LOGO = os.path.join(REPO, "aegis", "branding", "logo", "aegis-logo.png")
OUT = os.path.join(REPO, "aegis", "branding", "icons", "res_chromium_base")

BG = (7, 7, 7)          # sampled from the master logo background (#070707)
WHITE = (255, 255, 255)

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
LAYERED = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def mark_mask(img):
    """Isolate the mark (largest row-block of white pixels) -> alpha float 0..1."""
    rgb = np.asarray(img.convert("RGB"), dtype=np.float32)
    lum = rgb.mean(axis=2) / 255.0
    mask = lum > 0.5
    rows = np.where(mask.any(axis=1))[0]
    # split row ranges into blocks separated by gaps >= 15px; keep the tallest
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
    alpha = np.clip((alpha - 0.35) / 0.35, 0.0, 1.0)   # sharpen anti-aliased edges
    print(f"mark bbox: rows {top}-{bot}, cols {left}-{right} ({right - left}x{bot - top})")
    return alpha


def trace_paths(alpha, viewport=108.0, fit=62.0, center=None):
    """Trace contours -> Android pathData string in the given viewport.

    Returns (pathData, polys_in_viewport, iou). IoU compares the simplified
    polygons against the tracing raster, so fidelity is measured, not assumed.
    """
    if center is None:
        center = viewport / 2.0
    h, w = alpha.shape
    scale = 720.0 / max(h, w)
    small = cv2.resize(alpha, (int(round(w * scale)), int(round(h * scale))),
                       interpolation=cv2.INTER_AREA)
    sh, sw = small.shape
    mask8 = (small >= 0.5).astype(np.uint8)
    contours, hier = cv2.findContours(mask8, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE)
    k = fit / max(sh, sw)                      # contours live in the 720-space!
    ox, oy = center - (sw * k) / 2.0, center - (sh * k) / 2.0
    parts, polys_vp, polys_small = [], [], []
    for c in contours:
        if cv2.contourArea(c) < 6:                       # drop speckles
            continue
        eps = 0.0008 * cv2.arcLength(c, True) + 0.25
        poly = cv2.approxPolyDP(c, eps, True).reshape(-1, 2).astype(np.float64)
        polys_small.append(poly)
        pts = [f"{ox + x * k:.2f},{oy + y * k:.2f}" for x, y in poly]
        parts.append("M" + " L ".join(pts) + " Z")
        polys_vp.append(np.column_stack([ox + poly[:, 0] * k, oy + poly[:, 1] * k]))
    assert parts, "no contours traced"

    # --- fidelity check: hierarchy-aware raster vs original mask ----------
    raster = np.zeros_like(mask8)
    depth = {}
    for i, h4 in enumerate(hier[0]):
        d, p = 0, h4[3]
        while p != -1:
            d += 1
            p = hier[0][p][3]
        depth[i] = d
    for i, c in enumerate(contours):
        if cv2.contourArea(c) < 6:
            continue
        cv2.fillPoly(raster, [c.astype(np.int32)], 255 if depth[i] % 2 == 0 else 0)
    inter = np.logical_and(raster > 0, mask8 > 0).sum()
    union = np.logical_or(raster > 0, mask8 > 0).sum()
    iou = inter / union if union else 0.0

    xs = np.concatenate([p[:, 0] for p in polys_vp])
    ys = np.concatenate([p[:, 1] for p in polys_vp])
    print(f"vector bbox in viewport: x {xs.min():.1f}-{xs.max():.1f}, "
          f"y {ys.min():.1f}-{ys.max():.1f} (target fit {fit} @ center {center})")
    print(f"trace fidelity IoU: {iou:.4f}")
    assert iou > 0.97, f"traced vector deviates from the mark (IoU {iou:.3f})"
    return " ".join(parts), polys_vp, iou


def vector_xml(width_dp, viewport, fill, path, even_odd=True):
    fo = ' android:fillType="evenOdd"' if even_odd else ""
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Aegis Browser launcher icon - traced from aegis/branding/logo/aegis-logo.png -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{width_dp}dp"\n'
            f'    android:height="{width_dp}dp"\n'
            f'    android:viewportWidth="{viewport}"\n'
            f'    android:viewportHeight="{viewport}">\n'
            f'    <path\n'
            f'        android:pathData="{path}"\n'
            f'        android:fillColor="{fill}"{fo} />\n'
            f'</vector>\n')


def compose_mark(alpha, box, fill_frac=0.62):
    """Full-bleed brand-black square, white mark fitted to fill_frac of it."""
    inner = int(round(box * fill_frac))
    h, w = alpha.shape
    k = inner / max(h, w)
    tw, th = max(1, int(round(w * k))), max(1, int(round(h * k)))
    a = Image.fromarray((alpha * 255).astype(np.uint8)).resize((tw, th), Image.LANCZOS)
    canvas = Image.new("L", (box, box), 0)
    canvas.paste(a, ((box - tw) // 2, (box - th) // 2))
    out = Image.new("RGBA", (box, box), BG + (255,))
    white = Image.new("RGBA", (box, box), WHITE + (255,))
    out.paste(white, (0, 0), canvas)
    return out


def main():
    img = Image.open(LOGO)
    alpha = mark_mask(img)

    os.makedirs(OUT, exist_ok=True)
    for d in [f"mipmap-{d}" for d in LEGACY] + ["mipmap-nodpi", "drawable"]:
        os.makedirs(os.path.join(OUT, d), exist_ok=True)

    # --- bitmaps -----------------------------------------------------------
    # legacy launcher: mark ~68% of canvas (launchers apply their own masks)
    for dens, size in LEGACY.items():
        compose_mark(alpha, size, fill_frac=0.68).save(
            os.path.join(OUT, f"mipmap-{dens}", "app_icon.png"))
    # layered foreground bitmap: content must sit inside the 66/108 safe zone
    for dens, size in LAYERED.items():
        compose_mark(alpha, size, fill_frac=0.58).save(
            os.path.join(OUT, f"mipmap-{dens}", "layered_app_icon.png"))
        Image.new("RGB", (size, size), BG).save(
            os.path.join(OUT, f"mipmap-{dens}", "layered_app_icon_background.png"))

    # --- vectors -----------------------------------------------------------
    fg_path, polys_vp, iou = trace_paths(alpha, viewport=108, fit=62.0)
    with open(os.path.join(OUT, "mipmap-nodpi", "layered_app_icon_foreground.xml"), "w") as f:
        f.write(vector_xml(108, 108, "#FFFFFF", fg_path))
    mono_path, _, iou2 = trace_paths(alpha, viewport=90, fit=34.0, center=45.0)
    with open(os.path.join(OUT, "drawable", "themed_app_icon.xml"), "w") as f:
        f.write(vector_xml(90, 90, "#000000", mono_path))

    # --- preview: LEFT = vector as round launcher renders it,
    #               MID/RIGHT = legacy bitmaps at 192/96 -------------------
    vp = 108.0
    PX = 432
    vx = np.zeros((PX, PX), np.float32)
    for p in polys_vp:
        cv2.fillPoly(vx, [np.column_stack([p[:, 0] * PX / vp, p[:, 1] * PX / vp]).astype(np.int32)], 1.0)
    vec = np.full((PX, PX, 3), BG, np.uint8)
    vx3 = np.clip(vx * 255.0, 0, 255).astype(np.uint8)
    for ch in range(3):
        vec[:, :, ch] = np.maximum(vec[:, :, ch], vx3)
    vec_img = Image.fromarray(vec).convert("RGBA")
    mask = Image.new("L", (PX, PX), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, PX - 1, PX - 1), fill=255)
    round_img = Image.new("RGBA", (PX, PX), (0, 0, 0, 0))
    round_img.paste(vec_img, (0, 0), mask)
    legacy192 = compose_mark(alpha, 192, fill_frac=0.68)
    legacy96 = compose_mark(alpha, 96, fill_frac=0.68)
    strip = Image.new("RGB", (PX + 192 + 96 + 30, PX), (30, 30, 30))
    strip.paste(round_img.convert("RGB"), (0, 0))
    strip.paste(legacy192.convert("RGB"), (PX + 15, 0))
    strip.paste(legacy96.convert("RGB"), (PX + 192 + 15, 0))
    strip.save(os.path.join(OUT, "preview.png"))

    print("foreground pathData chars:", len(fg_path))
    print("OK ->", OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
