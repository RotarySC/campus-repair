"""
把**光晕式呼吸**的 4 个相位拼成一条对比带。

输入：`.scratch/v11-shots/halo-0..3.png` + `breathe-geo.json`
输出：`.scratch/v11-shots/halo-strip.png`

⚠️ 裁切框比上一版（`sheet-breathe.py`）**更宽**：现在点外面还有一圈 3px 的光晕
   （6px 点 + 3px 光晕 = 12px 外径），照旧框会把光晕切掉。

⚠️ 放大用 **NEAREST**：这个脚本的用途是看**真实渲染出来的像素**亮不亮，
   LANCZOS 会插值出一个"看起来更平滑"的光晕，反而让人误判它的实际浓度。

⚠️ 控制台是 GBK：**只 print ASCII**。
"""
import json
import os
from PIL import Image, ImageDraw

OUT = os.path.join(".scratch", "v11-shots")
PHASES = [0, 25, 50, 75]
ZOOM = 10

with open(os.path.join(OUT, "breathe-geo.json"), "r", encoding="utf-8") as f:
    geo = json.load(f)

dpr = geo["dpr"]
cx = geo["cx"] * dpr
cy = geo["cy"] * dpr
dot_px = geo["w"] * dpr
halo_px = (geo["w"] + 6) * dpr          # 点 + 两侧各 3px 光晕

left = int(cx - halo_px * 0.75)
top = int(cy - halo_px * 0.75)
right = int(cx + halo_px * 3.2)         # 右侧留出「加急」二字
bottom = int(cy + halo_px * 0.75)
box = (left, top, right, bottom)
print("crop box (device px):", box)
print("dot %.1f  halo %.1f  dpr=%s" % (dot_px, halo_px, dpr))

panels = []
print("\nphase -> halo centre-ish pixel RGB (measured on the untouched screenshot)")
for i in PHASES:
    im = Image.open(os.path.join(OUT, "halo-%d.png" % (i // 25))).convert("RGB")
    # 光晕环上的一点：从点中心往外 6 个设备像素（点半径 4.5 + 1.5）
    probe = im.getpixel((int(cx) + 6, int(cy)))
    print("  %3d%%  %s" % (i, probe))
    crop = im.crop(box)
    crop = crop.resize((crop.width * ZOOM, crop.height * ZOOM), Image.NEAREST)
    panels.append((i, crop))

pad = 24
label_h = 24
w = panels[0][1].width
h = panels[0][1].height
sheet = Image.new("RGB", (w + pad * 2, (h + label_h) * len(panels) + pad * 2), (255, 255, 255))
draw = ImageDraw.Draw(sheet)
y = pad
for i, panel in panels:
    draw.text((pad, y), "halo phase %d%%" % i, fill=(60, 60, 60))
    sheet.paste(panel, (pad, y + label_h))
    y += h + label_h

path = os.path.join(OUT, "halo-strip.png")
sheet.save(path)
print("\nsaved:", path, sheet.size)
