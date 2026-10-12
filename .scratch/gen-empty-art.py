#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""G2：空状态插画（生成式）—— 出图 + 量 + **按真实尺寸看**。

规范：docs/20261012-1330-…-visual-language-d-design.md §4.6 G2 / §4.7
约束原话：「**一套 2–3 张**（无数据/无结果/无权限），**不是一个空状态一张**；必须极简」。

## 三个状态怎么来的（不是拍脑袋，是数出来的）

全仓 `.el-empty` 的实际落点分三组：

  | 组 | 落点 | 数量 |
  |---|---|---|
  | no-data    | 控制台 3 张图、完工耗材核销、进销存入库历史、流转时间轴、调度池、站内信、`ResponsiveTable` 兜底 | 8+ |
  | no-result  | 完工页「未找到匹配的在库耗材」、调度中心「未检索到符合条件的维修师傅」 | 2 |
  | no-profile | 个人中心「尚未建立师傅档案，请联系管理员开通后查看作业数据」 | 1 |

第三组**不是「无权限」而是「未建档」**——照着它的实际文案取形（一张空白的证件卡），
这样也就顺带避开了与 A7（403 的那把挂锁）撞形的问题。

## 为什么这一批的判据与 G1 不同

G1 是**满屏背景**，「低对比、低细节、大留白」就是全部要求。
G2 是**卡片里 72~100px 的行内图**，所以真正的问题是：

  **它缩到 80px 还认得出吗？**

一条「很好看的 1024px 图」缩到 80px 完全可能变成一团灰雾。所以这里单列三条：

  · 背景必须是**纯白**（角点 >= 248）——否则贴在白卡片上会看见一个方块；
  · 缩到 **80px** 之后必须有对比（p1 <= 205）且**不是一团糊**（std >= 8）；
  · 灰阶（最大通道差 <= 8）——D 的语言是黑/白/灰，彩色在这里没有语义。

用法:
    python .scratch/gen-empty-art.py --seeds 20261012,777
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

BS = r"C:\Users\Rotary\Documents\Comprehensive\Files\Projects\BS"
OUT_ROOT = os.path.join(BS, ".superpowers", "g2")

BASE = (
    "A bold minimalist line-art icon for a web application empty state, centred on a pure white background. "
    "The subject is {subject}. "
    "Drawn with THICK, uniform, evenly weighted strokes of a clearly visible medium grey — "
    "distinctly darker than the white background, never pale, never washed out, never a hairline. "
    "Every stroke is bold and confident, roughly one fiftieth of the total image width, "
    "so that the drawing is still perfectly readable when it is reduced to a tiny 80 pixel thumbnail. "
    "Flat, graphic, iconic, evenly lit. "
    "The subject fills about two thirds of the frame, centred, with an even white margin around it. "
    "Pure white background, completely empty and clean. "
    "No texture, no noise, no grain, no gradient, no shading, no cast shadow, no 3D, no photographic realism. "
    "Strictly greyscale: only pure white and one single medium grey. No colour, no tint, no hue, no sepia. "
    "No text, no letters, no numbers, no labels, no captions, no logo, no watermark, "
    "no border, no frame, no background pattern, no decorative flourish."
)

SUBJECTS = [
    ("no-data",
     "one single empty open box seen from a three-quarter angle, completely empty inside, "
     "with its four lid flaps folded outward and flat"),
    ("no-result",
     "one magnifying glass with a round lens and one short straight handle, tilted about 30 degrees, "
     "the round lens perfectly empty and blank inside"),
    # ⚠️ 第一版这里写的是「虚线轮廓的证件卡」，缩到 80px 直接消失 —— 虚线 + 细线是最坏组合。
    # 而且「未建档」最通用的形其实是**头像占位符**，不是证件卡。
    ("no-profile",
     "one rounded square portrait frame containing a simple generic avatar placeholder: "
     "a small circle above a wide shallow arc"),
]


def build_workflow(prompt, steps, width, height, seed):
    """一条工作流 = 一次出图。开关全部走 qwen21_build 的真源，不手改 JSON。"""
    return qb.build(
        "t2i",
        prompt=prompt,
        steps=steps,        # 显式 20 —— turbo 的默认是 16，不显式给就会被补成 16
        width=width,
        height=height,
        seed=seed,
        turbo=True,         # 官方 Turbo LoRA
        te_speed=True,      # TE-Speed
        lazy_cache=False,   # 与 te_speed 互斥
    )


def analyse(png_bytes):
    from PIL import Image, ImageFilter

    im = Image.open(io.BytesIO(png_bytes)).convert("RGB")
    w, h = im.size

    # 灰阶：逐像素取三通道极差的最大值
    chan_max = max(max(p) - min(p) for p in im.getdata())

    lum = im.convert("L")
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
    std = statistics.pstdev(px)

    edge_mean = sum(lum.filter(ImageFilter.FIND_EDGES).getdata()) / len(px)

    # 四角 48×48：贴到白卡片上不能看见方块 ⇒ 必须接近纯白
    corner_min = 255
    for (x0, y0) in ((0, 0), (w - 48, 0), (0, h - 48), (w - 48, h - 48)):
        corner_min = min(corner_min, min(lum.crop((x0, y0, x0 + 48, y0 + 48)).getdata()))

    # 中央 60% 区域里的「墨迹」占比（< 230 算墨）
    cx0, cy0, cx1, cy1 = int(w * .2), int(h * .2), int(w * .8), int(h * .8)
    cen = list(lum.crop((cx0, cy0, cx1, cy1)).getdata())
    ink_ratio = sum(1 for v in cen if v < 230) / len(cen)

    # ★ 关键：缩到 80px 之后还剩多少对比、会不会糊成一团
    small = lum.resize((80, 80), Image.LANCZOS)
    sp = list(small.getdata())
    s_hist = small.histogram()
    s_total = sum(s_hist)

    def s_pct(p):
        acc, target = 0, s_total * p / 100.0
        for v, n in enumerate(s_hist):
            acc += n
            if acc >= target:
                return v
        return 255

    return {
        "size": f"{w}x{h}",
        "chan_max": chan_max,
        "p1": pct(1), "p50": pct(50), "p99": pct(99),
        "std": round(std, 1),
        "edge_mean": round(edge_mean, 2),
        "corner_min": corner_min,
        "ink_ratio": round(ink_ratio, 4),
        "at80_p1": s_pct(1), "at80_std": round(statistics.pstdev(sp), 1),
    }


def verdicts(m):
    return {
        "背景纯白 (角点>=248，否则贴白卡片上会看见方块)": m["corner_min"] >= 248,
        "灰阶 (最大通道差<=8，D 的语言是黑白灰)": m["chan_max"] <= 8,
        "极简 (边缘能量<=9，不是细节画)": m["edge_mean"] <= 9.0,
        "主体在中央且够大 (墨迹占比 3%~35%)": 0.03 <= m["ink_ratio"] <= 0.35,
        "★ 80px 还有对比 (p1<=205)": m["at80_p1"] <= 205,
        "★ 80px 不是一团糊 (std>=8)": m["at80_std"] >= 8.0,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seeds", default="20261012,777")
    ap.add_argument("--steps", type=int, default=20, help="与 G1 同配方（用户指定 20）")
    ap.add_argument("--size", type=int, default=1024)
    ap.add_argument("--transport", default="auto")
    ap.add_argument("--out", default=OUT_ROOT)
    ap.add_argument("--only", default="", help="只跑某一个状态，逗号分隔")
    a = ap.parse_args()

    seeds = [int(s) for s in a.seeds.split(",") if s.strip()]
    only = {s for s in a.only.split(",") if s.strip()}
    subjects = [s for s in SUBJECTS if not only or s[0] in only]
    os.makedirs(a.out, exist_ok=True)

    print("=== 出图参数 ===")
    print(f"模型   : Qwen-Image-2.1 + Turbo LoRA")
    print(f"加速   : TE-Speed    步数: {a.steps}    尺寸: {a.size}x{a.size}")
    print(f"seeds  : {seeds}")
    print(f"状态   : {[s[0] for s in subjects]}")

    jobs = []
    for name, subj in subjects:
        for seed in seeds:
            wf = build_workflow(BASE.format(subject=subj), a.steps, a.size, a.size, seed)
            info = qb.describe(wf)
            jobs.append((name, seed, wf, info))

    print("\n=== 工作流自检（开关真的在图上，不是「我以为在」）===")
    for name, seed, wf, info in jobs:
        print(f"  {name:<11} seed={seed:<10} steps={info['steps']} "
              f"size={info['width']}x{info['height']} turbo={info['turbo']} "
              f"te_speed={info['te_speed']} | {qb.acceleration_line(wf)}")

    with open(os.path.join(a.out, "workflow_%s_seed%d.json" % (subjects[0][0], seeds[0])), "w",
              encoding="utf-8") as f:
        json.dump(jobs[0][2], f, ensure_ascii=False, indent=2)

    results = []
    with RenderHost(transport=a.transport) as rh:
        free, total = rh.probe_free_memory()
        print(f"\n提交前显存: {free:.2f} / {total:.2f} GiB")
        if free < 8.0:
            print("显存偏低，先腾一次（手册 §5.7：跑完不释放）")
            rh.free()
            time.sleep(5)
            free, total = rh.probe_free_memory()
            print(f"腾过之后   : {free:.2f} / {total:.2f} GiB")

        for i, (name, seed, wf, info) in enumerate(jobs, 1):
            print(f"\n--- [{i}/{len(jobs)}] {name} seed={seed} ---")
            t0 = time.time()
            pid = rh.submit(wf)
            entry = rh.wait(pid, poll=2.0, timeout=3600)
            wall = time.time() - t0
            files = rh.download(pid, a.out, entry)
            print(f"  prompt_id : {pid}\n  墙钟      : {wall:.2f} s\n  落盘      : {[os.path.basename(x) for x in files]}")
            for path in files:
                with open(path, "rb") as fh:
                    data = fh.read()
                m = analyse(data)
                m.update({"name": name, "seed": seed, "wall_s": round(wall, 2),
                          "png_bytes": len(data), "file": path})
                v = verdicts(m)
                m["verdicts"] = v
                m["pass_n"] = sum(1 for x in v.values() if x)
                results.append(m)
                keys = ("chan_max", "p1", "p50", "p99", "std", "edge_mean",
                        "corner_min", "ink_ratio", "at80_p1", "at80_std")
                print(f"  度量      : {json.dumps({k: m[k] for k in keys}, ensure_ascii=False)}")
                for k, ok in v.items():
                    print(f"    {'PASS' if ok else 'FAIL'}  {k}")

                # 顺便存一张 80px 的真尺寸预览——**判据是判据，眼睛是眼睛**
                from PIL import Image
                im = Image.open(path).convert("RGB")
                im.resize((80, 80), Image.LANCZOS).save(
                    os.path.join(a.out, f"preview80_{name}_seed{seed}.png"))

    print("\n=== 汇总（按通过项数排序）===")
    for m in sorted(results, key=lambda x: -x["pass_n"]):
        print(f"  {m['name']:<11} seed={m['seed']:<10} 通过 {m['pass_n']}/6  "
              f"at80: p1={m['at80_p1']} std={m['at80_std']}  {os.path.basename(m['file'])}")

    with open(os.path.join(a.out, "metrics.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\n度量落盘: {os.path.join(a.out, 'metrics.json')}")


if __name__ == "__main__":
    main()
