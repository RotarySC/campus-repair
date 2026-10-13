"""
把呼吸的 4 个相位拼成一条对比带 —— 单张截图永远拍不出一个 1.8s 的循环。

输入：`.scratch/v9-shots/breathe-0..3.png`（同一枚「加急」点，动画被 pause + currentTime 冻结）
     `.scratch/v9-shots/breathe-geo.json`（点的 CSS 位置与 dpr）
输出：`.scratch/v9-shots/breathe-strip.png`

⚠️ 放大用 **NEAREST**：这个脚本的用途是看**真实渲染出来的像素**亮不亮，
   LANCZOS 会插值出一个"看起来更平滑"的点，反而让人误判它的实际对比度。

⚠️ 控制台是 GBK：**只 print ASCII**（本仓约定，见 render-host-manual）。
"""
import json
import os
from PIL import Image, ImageDraw

OUT = os.path.join(".scratch", "v9-shots")
PHASES = [0, 25, 50, 75]
ZOOM = 12

with open(os.path.join(OUT, "breathe-geo.json"), "r", encoding="utf-8") as f:
    geo = json.load(f)

dpr = geo["dpr"]
cx = geo["cx"] * dpr          # 点在截图（设备像素）里的中心
cy = geo["cy"] * dpr
dot_px = geo["w"] * dpr       # 点的直径（设备像素）

# 裁切框：左边留一点空、右边留出「加急」二字
left = int(cx - dot_px * 1.6)
top = int(cy - dot_px * 2.4)
right = int(cx + dot_px * 9.5)
bottom = int(cy + dot_px * 2.4)
box = (left, top, right, bottom)
print("crop box (device px):", box)
print("dot diameter (device px): %.1f  dpr=%s" % (dot_px, dpr))

panels = []
for i in PHASES:
    im = Image.open(os.path.join(OUT, "breathe-%d.png" % (i // 25))).convert("RGB")
    crop = im.crop(box)
    crop = crop.resize((crop.width * ZOOM, crop.height * ZOOM), Image.NEAREST)
    panels.append((i, crop))

# 实测点中心那一个像素的实际亮度 —— 把"看起来更淡"变成数字
print("\nphase -> dot centre pixel RGB (measured on the untouched screenshot)")
for i in PHASES:
    im = Image.open(os.path.join(OUT, "breathe-%d.png" % (i // 25))).convert("RGB")
    px = im.getpixel((int(cx), int(cy)))
    print("  %3d%%  %s" % (i, px))

pad = 28
label_h = 26
w = panels[0][1].width
h = panels[0][1].height
sheet = Image.new("RGB", (w + pad * 2, (h + label_h) * len(panels) + pad * 2), (255, 255, 255))
draw = ImageDraw.Draw(sheet)

y = pad
for i, panel in panels:
    draw.text((pad, y), "phase %d%%" % i, fill=(60, 60, 60))
    sheet.paste(panel, (pad, y + label_h))
    y += h + label_h

path = os.path.join(OUT, "breathe-strip.png")
sheet.save(path)
print("\nsaved:", path, sheet.size)
