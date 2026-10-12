#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把选中的 G2 候选**归一化后落进 `public/`**（规范 §4.6 G2）。

出图与发货是两件事，这一步只做**确定的**归一化，不做审美判断：

  1. **转灰阶** —— 模型输出有极淡的色偏（最大通道差 13~20/255，肉眼几乎看不出，
     但 D 的语言是黑白灰，彩色在这里没有语义）。与其在提示词里求它，不如在这里做死。
  2. **近白压成纯白** —— 角点最小值 248~249，贴在 `--surface`(#FFFFFF) 的白卡片上
     理论上会现出一个极淡的方块。把 >= 250 的一律压到 255，方块就绝对不存在。
  3. **缩到 240×240** —— 实机显示是 72~80px（`el-empty` 的 `image-size`），
     240 给到 3 倍 DPR 仍有像素可退。
  4. **WebP q=90** —— 线稿压缩率极高，实测每张 5~8 KB。

选片：**只取 seed 777 那一组**（三个状态各 5/6 通过项；20261012 那组 4/6，
且三张的角点都不过 248 —— 背景不纯白，贴到白卡片上会露方块）。

用法: python .scratch/ship-empty-art.py --seed 777 [--dry]
"""
import argparse
import json
import os
from PIL import Image

BS = r"C:\Users\Rotary\Documents\Comprehensive\Files\Projects\BS"
OUT = os.path.join(BS, ".superpowers", "g2")
PUB = os.path.join(BS, "apps", "admin-web", "public", "empty")

SIZE = 240
WHITE_CLAMP = 250


def normalise(path):
    im = Image.open(path).convert("RGB")
    # 1) 灰阶（Rec.601，与 Pillow 的 convert("L") 一致）
    lum = im.convert("L")
    # 2) 近白压成纯白
    lum = lum.point(lambda v: 255 if v >= WHITE_CLAMP else v)
    # 3) 缩放
    lum = lum.resize((SIZE, SIZE), Image.LANCZOS)
    return lum.convert("RGB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=777)
    ap.add_argument("--dry", action="store_true")
    a = ap.parse_args()

    with open(os.path.join(OUT, "metrics.json"), encoding="utf-8") as f:
        metrics = json.load(f)
    picks = {m["name"]: m for m in metrics if m["seed"] == a.seed}
    missing = [n for n in ("no-data", "no-result", "no-profile") if n not in picks]
    if missing:
        raise SystemExit("metrics.json 里找不到这些状态的 seed=%d：%s" % (a.seed, missing))

    os.makedirs(PUB, exist_ok=True)
    print("=== 归一化并落盘（seed=%d）===" % a.seed)
    for name in ("no-data", "no-result", "no-profile"):
        src = picks[name]["file"]
        im = normalise(src)
        dst = os.path.join(PUB, name + ".webp")
        if not a.dry:
            im.save(dst, "WEBP", quality=90, method=6)
        kb = os.path.getsize(dst) / 1024.0 if not a.dry else 0

        px = list(im.convert("L").getdata())
        corner_min = 255
        for (x0, y0) in ((0, 0), (SIZE - 48, 0), (0, SIZE - 48), (SIZE - 48, SIZE - 48)):
            corner_min = min(corner_min, min(im.convert("L").crop((x0, y0, x0 + 48, y0 + 48)).getdata()))
        print("  %-11s <- %s" % (name, os.path.basename(src)))
        print("              原始: chan_max=%d corner_min=%d at80(p1=%d,std=%.1f)"
              % (picks[name]["chan_max"], picks[name]["corner_min"],
                 picks[name]["at80_p1"], picks[name]["at80_std"]))
        print("              发货: %d×%d  corner_min=%d  %.1f KB  -> %s"
              % (SIZE, SIZE, corner_min, kb, os.path.relpath(dst, BS)))


if __name__ == "__main__":
    main()
