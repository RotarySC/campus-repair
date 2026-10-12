#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""分类色板：「受限」到底能限什么 —— 回答「受限色板与改前有什么区别」。

先把一个可能被忽略的前提**算出来**：D 已经锁定了 **7 个有彩色相**的状态色
（h1~h7；h8 是灰）散布在整个色轮上。那么「分类色板不借用状态色相」这件事
**还有没有空间**？—— 如果色轮已经被占满，那「受限」就限不了这一条，
能限的只剩明度与彩度。先算，再谈。

同时渲染一张对照图（`.superpowers/palette/compare.png`）：改前 / 候选甲 / 候选乙，
以及「哪些色号与状态色重合」的标注。

用法: python .scratch/palette-options.py
"""
import colorsys
import math
import os

from PIL import Image, ImageDraw, ImageFont

BS = r"C:\Users\Rotary\Documents\Comprehensive\Files\Projects\BS"
OUT = os.path.join(BS, ".superpowers", "palette")

CURRENT = ['#1677FF', '#52C41A', '#FAAD14', '#FA541C', '#722ED1', '#13C2C2', '#EB2F96', '#8C8C8C']
STATUS = {
    'h1 待派单': '#FA8C16', 'h2 待承接': '#13C2C2', 'h3 维修中': '#1677FF', 'h4 已挂起': '#6B7C93',
    'h5 待验收': '#722ED1', 'h6 返工待派': '#F5222D', 'h7 已结单': '#52C41A', 'h8 已作废': '#B3B3B3',
}


def hex2rgb(h):
    s = h.lstrip('#')
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def rgb2lab(rgb):
    r, g, b = [v / 255 for v in rgb]
    f = lambda v: ((v + 0.055) / 1.055) ** 2.4 if v > 0.04045 else v / 12.92
    r, g, b = f(r), f(g), f(b)
    X = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
    Y = r * 0.2126 + g * 0.7152 + b * 0.0722
    Z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
    k = lambda t: t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    fx, fy, fz = k(X), k(Y), k(Z)
    return (116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))


def lab(h):
    return rgb2lab(hex2rgb(h))


def dE(a, b):
    la, lb = lab(a), lab(b)
    return math.dist(la, lb)


def chroma(h):
    _, a, b = lab(h)
    return math.hypot(a, b)


def hue(h):
    _, a, b = lab(h)
    return (math.degrees(math.atan2(b, a)) + 360) % 360


def lightness(h):
    return lab(h)[0]


print('=== PREMISE: how much room is left on the hue wheel? ===')
chromatic = {k: v for k, v in STATUS.items() if k != 'h8 已作废'}
hs = sorted((hue(v), k) for k, v in chromatic.items())
print('\n  status hue angles:')
for ang, k in hs:
    print('    %6.1f deg   %s  %s' % (ang, k, chromatic[k]))

gaps = []
for i in range(len(hs)):
    a = hs[i][0]
    b = hs[(i + 1) % len(hs)][0]
    g = (b - a) % 360
    gaps.append((g, a, b))
gaps.sort(reverse=True)
print('\n  4 largest empty spans on the wheel:')
for g, a, b in gaps[:4]:
    print('    %5.1f deg   %6.1f -> %6.1f' % (g, a, b))
print('\n  NOTE: 7 chromatic status hues already cut the wheel into 7 arcs;')
print('        the widest span is only %.1f deg.' % gaps[0][0])
print('        Fitting 8 further hues that all stay far (say dE > 15) from every')
print('        status hue is ARITHMETICALLY IMPOSSIBLE. So "never borrow a status')
print('        hue" is NOT an implementable constraint for an 8-colour palette.')


def nearest_status(c):
    return min(((dE(c, v), k) for k, v in STATUS.items()))


print('\n=== STRUCTURE OF THE CURRENT PALETTE ===')
print('  hex        L*     C*   hue-ang  nearest-status    dE')
for c in CURRENT:
    d, k = nearest_status(c)
    flag = '  <-- SAME COLOUR' if d < 6 else ''
    print('  %s  %5.1f  %5.1f  %6.1f  %-10s  %5.1f%s' % (c, lightness(c), chroma(c), hue(c), k, d, flag))
Ls = [lightness(c) for c in CURRENT]
Cs = [chroma(c) for c in CURRENT]
same = sum(1 for c in CURRENT if nearest_status(c)[0] < 6)
print('\n  identical to a status hue (dE<6): %d / 8' % same)
print('  L* spread: %.1f   (heaviest %s L*=%.1f  vs  lightest %s L*=%.1f)'
      % (max(Ls) - min(Ls),
         CURRENT[Ls.index(min(Ls))], min(Ls), CURRENT[Ls.index(max(Ls))], max(Ls)))
print('  C* spread: %.1f   (grey %s C*=%.1f next to C*=%.1f)'
      % (max(Cs) - min(Cs), CURRENT[Cs.index(min(Cs))], min(Cs), max(Cs)))

adj = [dE(CURRENT[i - 1], CURRENT[i]) for i in range(1, len(CURRENT))]
print('  adjacent dE min=%.1f  (in a pie only neighbours must be told apart)' % min(adj))

# ---------------- 候选 ----------------
# 甲：等明度、等彩度、绕一圈。这是「受限」能做到的那部分。
L_FIX, C_FIX = 62.0, 48.0
CAND_A = []
for i in range(8):
    ang = math.radians(20 + i * 45)
    a_, b_ = C_FIX * math.cos(ang), C_FIX * math.sin(ang)
    # Lab -> sRGB（D65）
    fy = (L_FIX + 16) / 116
    fx, fz = fy + a_ / 500, fy - b_ / 200
    g = lambda t: t ** 3 if t ** 3 > 0.008856 else (t - 16 / 116) / 7.787
    X, Y, Z = 0.95047 * g(fx), 1.0 * g(fy), 1.08883 * g(fz)
    r = X * 3.2406 + Y * -1.5372 + Z * -0.4986
    gg = X * -0.9689 + Y * 1.8758 + Z * 0.0415
    bb = X * 0.0557 + Y * -0.2040 + Z * 1.0570
    enc = lambda v: 1.055 * (v ** (1 / 2.4)) - 0.055 if v > 0.0031308 else 12.92 * v
    rgb = tuple(min(255, max(0, round(enc(v) * 255))) for v in (r, gg, bb))
    CAND_A.append('#%02X%02X%02X' % rgb)

# 乙：不靠色相，靠明度阶梯（同一支灰，8 档）
CAND_B = []
for i in range(8):
    v = round(28 + i * 22)
    CAND_B.append('#%02X%02X%02X' % (v, v, v))

for name, pal in (('甲 等明度等彩度环', CAND_A), ('乙 单一灰阶 8 档', CAND_B)):
    Ls2 = [lightness(c) for c in pal]
    Cs2 = [chroma(c) for c in pal]
    ad = [dE(pal[i - 1], pal[i]) for i in range(1, len(pal))]
    mn = min(dE(pal[i], pal[j]) for i in range(8) for j in range(i + 1, 8))
    print('\n=== 候选%s ===' % name)
    print('  色号      ' + '  '.join(pal))
    print('  L* 极差 %.1f   C* 极差 %.1f   相邻 ΔE min=%.1f   全对 ΔE min=%.1f'
          % (max(Ls2) - min(Ls2), max(Cs2) - min(Cs2), min(ad), mn))
    print('  最近状态色 ΔE min=%.1f' % min(nearest_status(c)[0] for c in pal))

# ---------------- 对照图 ----------------
os.makedirs(OUT, exist_ok=True)
try:
    f14 = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 14)
    f16 = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 16)
except Exception:
    f14 = f16 = ImageFont.load_default()

SW, SH, GAPX = 76, 64, 6
W, H = 20 + SW * 8 + GAPX * 7 + 20, 470
img = Image.new('RGB', (W, H), (255, 255, 255))
d = ImageDraw.Draw(img)

rows = [
    ('改前（现状）', CURRENT, True),
    ('候选甲 等明度等彩度环', CAND_A, False),
    ('候选乙 单一灰阶 8 档', CAND_B, False),
]
y = 16
for label, pal, mark in rows:
    d.text((20, y), label, fill=(0, 0, 0), font=f16)
    y += 26
    for i, c in enumerate(pal):
        x = 20 + i * (SW + GAPX)
        d.rectangle([x, y, x + SW, y + SH], fill=hex2rgb(c))
        d.text((x + 6, y + SH + 4), c, fill=(90, 90, 90), font=f14)
        if mark:
            dd, k = nearest_status(c)
            if dd < 6:
                d.text((x + 6, y + SH + 22), '= ' + k.split()[1], fill=(200, 0, 0), font=f14)
    y += SH + 48 + 18

d.text((20, y), '红字 = 与 D 锁定的状态色完全同一个色号（ΔE<6）', fill=(200, 0, 0), font=f14)
y += 24
d.text((20, y), '状 态 色：', fill=(0, 0, 0), font=f16)
for i, (k, c) in enumerate(STATUS.items()):
    x = 110 + i * (SW + GAPX)
    d.rectangle([x, y - 4, x + SW, y + SH - 4], fill=hex2rgb(c))
    d.text((x + 4, y + SH + 2), k.split()[0], fill=(90, 90, 90), font=f14)

out = os.path.join(OUT, 'compare.png')
img.save(out)
print('\n对照图: ' + out)
