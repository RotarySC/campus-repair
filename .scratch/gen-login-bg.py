#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""G1：登录页背景图（生成式）—— 出图 + 按 D 的口径**量出来**再选。

规范：docs/20261012-1330-…-visual-language-d-design.md §4.6 G1 / §4.7
约束原话：「提示词必须把 D 变成约束：低对比、低细节、大留白、无强渐变、可压到极小体积」。

**把上面那五个词变成可测量的判据**，否则「低对比」只是我自己的感觉：

  | 词 | 判据 | 阈值 |
  |---|---|---|
  | 低对比 | 亮度 p1 / p99 的跨度 | p1 ≥ 215 且 p99 ≤ 252 且 (p99−p1) ≤ 40 |
  | 低细节 | 边缘能量（FIND_EDGES 均值） | ≤ 6.0 |
  | 大留白 | **中央区**标准差 < 边缘区标准差 | 且中央 std ≤ 6.0 |
  | 无强渐变 | 四象限均值极差 | ≤ 12 |
  | 可压到极小体积 | WebP q=80 字节数 | ≤ 120 KB（并记录实际值） |
  | 无暗角 | 四个角 64×64 补丁的最小亮度 | ≥ 215 |

出图参数（用户指定）：**Qwen-Image-2.1 Turbo** LoRA + **TE-Speed** 加速节点 + **20 步**。
`turbo=True` 本身会把"没表态"的步数补成 16 —— 所以 20 必须**显式传**（见 qwen21_build.build 第 457~464 行）。

用法:
    python .scratch/gen-login-bg.py --seeds 12345,777,20261012
"""
import argparse
import io
import json
import os
import statistics
import sys
import time

sys.path.insert(0, r"C:\Users\Rotary\Documents\Comprehensive\Files\Projects\dsh\ComfyUI\tools")
sys.path.insert(0, r"C:\Users\Rotary\Documents\Comprehensive\Files\Projects\dsh\ComfyUI\qwen21-workbench")

from render_host_client import RenderHost  # noqa: E402
import qwen21_build as qb  # noqa: E402

OUT_ROOT = os.path.join(
    r"C:\Users\Rotary\Documents\Comprehensive\Files\Projects\BS", ".superpowers", "g1"
)

# D 的口径全部写进正向提示词。**反向词没用**：CFG=1.0 时负向分支根本不被求值
# （AGENTS.md「反向词：CFG=1.0 时它一个字都不生效」），所以「不要什么」也得放这儿。
PROMPT = (
    "Extremely minimal high-key wide-format background photograph for a corporate web login page. "
    "A vast, almost featureless pale cool grey-white interior of a modern campus building, "
    "soft flat overcast daylight, matte pale concrete and plaster surfaces. "
    "Only along the extreme left and right edges, a few barely visible out-of-focus vertical forms "
    "suggest distant columns and a doorway, so softly defocused that they read as the faintest tonal suggestion. "
    "The entire central two thirds is one uninterrupted calm flat field of pale grey-white. "
    "Tonal range extremely narrow and very light: nothing darker than a light grey around #E2E6EC, "
    "nothing brighter than #FBFCFD. Very low contrast, white on white, almost monochrome, desaturated. "
    "Flat even illumination, no shadows, no dark corners, no vignette, no strong gradient, no bright highlights. "
    "Medium format photographic, extremely shallow depth of field, deliberately overexposed by one stop, "
    "gentle film grain, quiet, empty, restrained, architectural, generous negative space. "
    "No text, no letters, no numbers, no signage, no people, no furniture, no plants, no logo, no watermark, "
    "no pattern, no bokeh."
)


def build_workflow(prompt, steps, width, height, seed):
    """一条工作流 = 一次出图。开关全部走 qwen21_build 的真源，不手改 JSON。"""
    return qb.build(
        "t2i",
        prompt=prompt,
        steps=steps,          # 显式 20 —— turbo 的默认是 16，不显式给就会被补成 16
        width=width,
        height=height,
        seed=seed,
        turbo=True,           # 官方 Turbo LoRA，插在 TE-Speed 之前（builder 负责顺序）
        te_speed=True,        # TE-Speed，本仓默认就开；显式写出来是为了可读
        lazy_cache=False,     # 与 te_speed 互斥
    )


# ---------------- 度量 ----------------

def analyse(png_bytes):
    from PIL import Image, ImageFilter

    im = Image.open(io.BytesIO(png_bytes)).convert("RGB")
    lum = im.convert("L")
    w, h = lum.size
    hist = lum.histogram()
    total = sum(hist)

    def pct(p):
        acc, target = 0, total * p / 100.0
        for v, n in enumerate(hist):
            acc += n
            if acc >= target:
                return v
        return 255

    px = list(lum.getdata())
    mean = sum(px) / len(px)
    std = statistics.pstdev(px)

    edges = lum.filter(ImageFilter.FIND_EDGES)
    edge_mean = sum(edges.getdata()) / len(px)

    # 中央 1/3 宽 × 1/2 高（登录卡落在这里）vs 左右两条边带
    cx0, cx1 = w // 3, w * 2 // 3
    cy0, cy1 = h // 4, h * 3 // 4
    centre = list(lum.crop((cx0, cy0, cx1, cy1)).getdata())
    left = list(lum.crop((0, 0, w // 6, h)).getdata())
    right = list(lum.crop((w - w // 6, 0, w, h)).getdata())
    centre_std = statistics.pstdev(centre)
    edge_std = statistics.pstdev(left + right)

    quads = []
    for (x0, y0, x1, y1) in ((0, 0, w // 2, h // 2), (w // 2, 0, w, h // 2),
                             (0, h // 2, w // 2, h), (w // 2, h // 2, w, h)):
        q = list(lum.crop((x0, y0, x1, y1)).getdata())
        quads.append(sum(q) / len(q))
    quad_spread = max(quads) - min(quads)

    corner_min = 255
    for (x0, y0) in ((0, 0), (w - 64, 0), (0, h - 64), (w - 64, h - 64)):
        c = list(lum.crop((x0, y0, x0 + 64, y0 + 64)).getdata())
        corner_min = min(corner_min, min(c))

    buf = io.BytesIO()
    im.save(buf, "WEBP", quality=80, method=6)
    webp_kb = len(buf.getvalue()) / 1024.0

    return {
        "size": f"{w}x{h}",
        "p1": pct(1), "p50": pct(50), "p99": pct(99),
        "spread": pct(99) - pct(1),
        "mean": round(mean, 1), "std": round(std, 2),
        "edge_mean": round(edge_mean, 2),
        "centre_std": round(centre_std, 2), "edge_std": round(edge_std, 2),
        "quad_spread": round(quad_spread, 2),
        "corner_min": corner_min,
        "webp_kb": round(webp_kb, 1),
    }


def verdicts(m):
    return {
        "低对比 (p1>=215, p99<=252, 跨度<=40)":
            m["p1"] >= 215 and m["p99"] <= 252 and m["spread"] <= 40,
        "低细节 (边缘能量<=6.0)": m["edge_mean"] <= 6.0,
        "大留白 (中央std<=6 且 < 边缘std)": m["centre_std"] <= 6.0 and m["centre_std"] < m["edge_std"],
        "无强渐变 (四象限极差<=12)": m["quad_spread"] <= 12.0,
        "无暗角 (角落最小>=215)": m["corner_min"] >= 215,
        "可压到极小体积 (webp<=120KB)": m["webp_kb"] <= 120.0,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seeds", default="12345,777,20261012")
    ap.add_argument("--steps", type=int, default=20, help="用户指定 20")
    ap.add_argument("--width", type=int, default=1536)
    ap.add_argument("--height", type=int, default=864)
    ap.add_argument("--transport", default="auto")
    ap.add_argument("--out", default=OUT_ROOT)
    a = ap.parse_args()

    seeds = [int(s) for s in a.seeds.split(",") if s.strip()]
    os.makedirs(a.out, exist_ok=True)

    print(f"=== 出图参数（用户指定）===")
    print(f"模型      : Qwen-Image-2.1 + Turbo LoRA")
    print(f"加速      : TE-Speed")
    print(f"步数      : {a.steps}")
    print(f"尺寸      : {a.width}x{a.height}")
    print(f"seeds     : {seeds}")

    # 先本地把图搭出来并**打出来**——开关真的在图上，不是"我以为在"
    wfs = {}
    for s in seeds:
        wf = build_workflow(PROMPT, a.steps, a.width, a.height, s)
        wfs[s] = wf
        info = qb.describe(wf)
        print(f"\n=== workflow seed={s} ===")
        print(f"  steps/size/seed : {info['steps']} / {info['width']}x{info['height']} / {info['seed']}")
        print(f"  加速链          : {qb.acceleration_line(wf)}")
        print(f"  turbo={qb.describe(wf)['turbo']}  te_speed={qb.describe(wf)['te_speed']}  lazy_cache={qb.describe(wf)['lazy_cache']}")

    with open(os.path.join(a.out, "workflow_seed%d.json" % seeds[0]), "w", encoding="utf-8") as f:
        json.dump(wfs[seeds[0]], f, ensure_ascii=False, indent=2)

    results = []
    with RenderHost(transport=a.transport) as rh:
        free, total = rh.probe_free_memory()
        print(f"\n提交前显存: {free:.2f} / {total:.2f} GiB")
        for s in seeds:
            t0 = time.time()
            pid = rh.submit(wfs[s])
            entry = rh.wait(pid, poll=2.0, timeout=3600)
            wall = time.time() - t0
            status = entry.get("status", {}).get("status_str") if isinstance(entry.get("status"), dict) else None
            files = rh.download(pid, a.out, entry)
            print(f"\n--- seed {s} ---")
            print(f"  prompt_id : {pid}")
            print(f"  status    : {status}")
            print(f"  墙钟      : {wall:.2f} s")
            print(f"  落盘      : {files}")
            for path in files:
                with open(path, "rb") as fh:
                    data = fh.read()
                m = analyse(data)
                m["seed"] = s
                m["wall_s"] = round(wall, 2)
                m["png_bytes"] = len(data)
                m["file"] = path
                v = verdicts(m)
                m["verdicts"] = v
                m["pass_n"] = sum(1 for x in v.values() if x)
                results.append(m)
                print(f"  度量      : {json.dumps({k: m[k] for k in ('p1','p50','p99','spread','std','edge_mean','centre_std','edge_std','quad_spread','corner_min','webp_kb')}, ensure_ascii=False)}")
                for k, ok in v.items():
                    print(f"    {'PASS' if ok else 'FAIL'}  {k}")

    print("\n=== 汇总（按通过项数排序）===")
    for m in sorted(results, key=lambda x: -x["pass_n"]):
        print(f"  seed={m['seed']:<10} 通过 {m['pass_n']}/6  {m['file']}")

    with open(os.path.join(a.out, "metrics.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\n度量落盘: {os.path.join(a.out, 'metrics.json')}")


if __name__ == "__main__":
    main()
