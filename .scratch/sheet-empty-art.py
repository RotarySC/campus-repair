#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把 G2 的候选拼成一张联表：**上半是 1024 缩到 240，下半是实机 80px 1:1**。

为什么要拼：判据只会说「80px 的 std 不够」，它不会告诉你**画的是什么**、形状对不对。
而「画的是什么」只能看。下半那一行是这个批次的真问题——图缩到 80px 还认不认得出。

用法: python .scratch/sheet-empty-art.py [seed]
产物: .superpowers/g2/sheet_seed<seed>.png
"""
import json
import os
import sys
from PIL import Image

OUT = os.path.join(r"C:\Users\Rotary\Documents\Comprehensive\Files\Projects\BS", ".superpowers", "g2")
NAMES = ["no-data", "no-result", "no-profile"]

seed = sys.argv[1] if len(sys.argv) > 1 else "777"

# ⚠️ 从 metrics.json 里按 (name, seed) 找文件，**不硬编码自增文件名** ——
#    ComfyUI 的 Qwen_image_2.1_000NN.png 是全局计数器，每跑一批就整体后移，
#    写死编号的话第二次重跑就会对着上一批的图评头论足（而它看起来完全正常）。
with open(os.path.join(OUT, "metrics.json"), encoding="utf-8") as f:
    metrics = json.load(f)
pick = {}
for m in metrics:
    if str(m["seed"]) == str(seed):
        pick[m["name"]] = m

BIG = 240
LABEL_H = 30
PAD = 16
GAP = 20
W = PAD * 2 + BIG * 3 + GAP * 2
H = PAD * 2 + LABEL_H + BIG + GAP + 80 + LABEL_H + 20

sheet = Image.new("RGB", (W, H), (255, 255, 255))

from PIL import ImageDraw, ImageFont
try:
    font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 15)
except Exception:
    font = ImageFont.load_default()

d = ImageDraw.Draw(sheet)
for i, name in enumerate(NAMES):
    x = PAD + i * (BIG + GAP)
    y = PAD
    m = pick.get(name)
    if not m:
        d.text((x, y), name + " (缺)", fill=(200, 0, 0), font=font)
        continue

    d.text((x, y), "%s  通过 %d/6" % (name, m["pass_n"]), fill=(0, 0, 0), font=font)
    y += LABEL_H

    im = Image.open(m["file"]).convert("RGB")
    sheet.paste(im.resize((BIG, BIG), Image.LANCZOS), (x, y))
    y += BIG + GAP

    d.text((x, y), "80px  p1=%d std=%.1f" % (m["at80_p1"], m["at80_std"]),
           fill=(120, 120, 120), font=font)
    y += LABEL_H
    sheet.paste(im.resize((80, 80), Image.LANCZOS), (x, y))

out = os.path.join(OUT, "sheet_seed%s.png" % seed)
sheet.save(out)
print(out)
