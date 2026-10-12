#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""G1 选图 → 导出 WebP 到前端 public/。

选图不靠"看着好看"，靠 `gen-login-bg.py` 落在 `metrics.json` 里的 6 项判据（通过数最多者胜，
平票按 WebP 体积小者）。**这一步和下一步（可选）的调色都不改构图**，只做格式与（如需）色阶。

用法:
    python .scratch/pick-login-bg.py --seed 777
    python .scratch/pick-login-bg.py --seed 777 --quality 82
    python .scratch/pick-login-bg.py --seed 777 --level 225 250   # 可选：把亮度压进 [225,250]
"""
import argparse
import io
import json
import os

BASE = r"C:\Users\Rotary\Documents\Comprehensive\Files\Projects\BS"
SRC_DIR = os.path.join(BASE, ".superpowers", "g1")
DEST = os.path.join(BASE, "apps", "admin-web", "public", "login-bg.webp")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, required=True)
    ap.add_argument("--quality", type=int, default=80)
    ap.add_argument("--level", nargs=2, type=int, default=None,
                    metavar=("BLACK", "WHITE"),
                    help="可选：把亮度重映射到 [BLACK, WHITE]。不给就原样导出")
    a = ap.parse_args()

    from PIL import Image

    with open(os.path.join(SRC_DIR, "metrics.json"), encoding="utf-8") as f:
        metrics = json.load(f)
    m = next(x for x in metrics if x["seed"] == a.seed)
    src = m["file"]
    print(f"源图    : {src}")
    print(f"度量    : p1={m['p1']} p50={m['p50']} p99={m['p99']} 跨度={m['spread']} "
          f"边缘={m['edge_mean']} 中央std={m['centre_std']} 角min={m['corner_min']}")
    for k, ok in m["verdicts"].items():
        print(f"  {'PASS' if ok else 'FAIL'}  {k}")

    im = Image.open(src).convert("RGB")
    if a.level:
        black, white = a.level
        lut = [max(0, min(255, round((v - black) * 255.0 / (white - black)))) for v in range(256)]
        im = im.point(lut * 3)
        print(f"色阶    : 重映射 [{black}, {white}] → [0, 255]（**已偏离模型原始输出，如实记录**）")

    buf = io.BytesIO()
    im.save(buf, "WEBP", quality=a.quality, method=6)
    data = buf.getvalue()

    os.makedirs(os.path.dirname(DEST), exist_ok=True)
    with open(DEST, "wb") as f:
        f.write(data)

    # 回读自证：落盘的是不是我以为的那个格式/尺寸/大小
    back = Image.open(DEST)
    print(f"\n落盘    : {DEST}")
    print(f"格式    : {back.format} 尺寸 {back.size[0]}x{back.size[1]} 模式 {back.mode}")
    print(f"体积    : {len(data)} B = {len(data)/1024:.1f} KB (q={a.quality})")


if __name__ == "__main__":
    main()
