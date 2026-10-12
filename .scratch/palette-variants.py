#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""分类色板：三个变体并排看。

「等 Lab 彩度」在数值上齐，但**暖色看起来更闷** —— Lab 的 C* 不是感知彩度，
黄/橙要更高的 C* 才和蓝/青一样"有色"。所以第二版试「每个色相取 sRGB 能给的最大彩度」，
让暖色多拿一点。第三版在两者之间。

判据仍然全列出来（明度极差 / 彩度极差 / 相邻 ΔE / 与状态色最近 ΔE），
但**最终选哪一版靠看图** —— 数值齐不等于看起来齐。

用法: python .scratch/palette-variants.py
产物: .superpowers/palette/variants.png
"""
import math
import os

from PIL import Image, ImageDraw, ImageFont

BS = r"C:\Users\Rotary\Documents\Comprehensive\Files\Projects\BS"
OUT = os.path.join(BS, ".superpowers", "palette")

OLD = ['#1677FF', '#52C41A', '#FAAD14', '#FA541C', '#722ED1', '#13C2C2', '#EB2F96', '#8C8C8C']
STATUS = {'h1': '#FA8C16', 'h2': '#13C2C2', 'h3': '#1677FF', 'h4': '#6B7C93',
          'h5': '#722ED1', 'h6': '#F5222D', 'h7': '#52C41A', 'h8': '#B3B3B3'}
CHROMATIC = ['h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'h7']
N = 8
OFFSET = 39           # 由 .scratch/derive-category-palette.py 的搜索给出：离最近状态色最远


def hex2rgb(h):
    s = h.lstrip('#')
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def rgb2lab(rgb):
    r, g, b = [v / 255 for v in rgb]
    f = lambda v: ((v + 0.055) / 1.055) ** 2.4 if v > 0.04045 else v / 12.92
    r, g, b = f(r), f(g), f(b)
    X = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
    Y = r * 0.2126 + g * 0.7152 + b * 0.0722
    Z = r * 0.0193 + g * 0.1192 + b * 0.9505 / 1.08883 * 1.08883
    Z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
    k = lambda t: t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    fx, fy, fz = k(X), k(Y), k(Z)
    return (116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))


def lab(h):
    return rgb2lab(hex2rgb(h))


def dE(a, b):
    return math.dist(lab(a), lab(b))


def lab2hex(L, a, b):
    fy = (L + 16) / 116
    fx, fz = fy + a / 500, fy - b / 200
    g = lambda t: t ** 3 if t ** 3 > 0.008856 else (t - 16 / 116) / 7.787
    X, Y, Z = 0.95047 * g(fx), 1.0 * g(fy), 1.08883 * g(fz)
    r = X * 3.2406 + Y * -1.5372 + Z * -0.4986
    gg = X * -0.9689 + Y * 1.8758 + Z * 0.0415
    bb = X * 0.0557 + Y * -0.2040 + Z * 1.0570
    enc = lambda v: 1.055 * (v ** (1 / 2.4)) - 0.055 if v > 0.0031308 else 12.92 * v
    return '#%02X%02X%02X' % tuple(min(255, max(0, round(enc(v) * 255))) for v in (r, gg, bb))


def at(L, C, hue):
    return lab2hex(L, C * math.cos(math.radians(hue)), C * math.sin(math.radians(hue)))


def max_chroma(L, hue):
    """这个 (L, hue) 在 sRGB 里能拿到多大 C*"""
    lo, hi = 0.0, 140.0
    for _ in range(28):
        mid = (lo + hi) / 2
        back = lab(at(L, mid, hue))
        ok = math.hypot(back[1], back[2]) >= mid - 0.6
        if ok:
            lo = mid
        else:
            hi = mid
    return lo


def variant(kind, L):
    pal = []
    for i in range(N):
        hue = OFFSET + i * 45
        if kind == 'equal':
            pal.append(at(L, 34.0, hue))
        elif kind == 'max':
            pal.append(at(L, max_chroma(L, hue), hue))
        else:  # 'capped' —— 取 sRGB 最大彩度，但压到 55，避免暖色爆掉
            pal.append(at(L, min(max_chroma(L, hue), 55.0), hue))
    return pal


VARIANTS = [
    ('A  L*=60 等彩度 34  (equal Lab chroma)', variant('equal', 60)),
    ('B  L*=60 各色相取 sRGB 最大彩度 (perceptual-ish)', variant('max', 60)),
    ('C  L*=60 最大彩度但压到 55 (capped)', variant('capped', 60)),
    ('D  L*=66 最大彩度但压到 55', variant('capped', 66)),
]


def report(label, pal):
    Ls = [lab(c)[0] for c in pal]
    Cs = [math.hypot(lab(c)[1], lab(c)[2]) for c in pal]
    adj = min(dE(pal[i - 1], pal[i]) for i in range(1, N))
    allp = min(dE(pal[i], pal[j]) for i in range(N) for j in range(i + 1, N))
    st = min(min(dE(c, STATUS[k]) for k in CHROMATIC) for c in pal)
    print('%-46s  L*spread %5.1f  C*spread %5.1f  adjdE %5.1f  mindE %5.1f  toStatus %5.1f'
          % (label, max(Ls) - min(Ls), max(Cs) - min(Cs), adj, allp, st))
    print('     ' + '  '.join(pal))


print('=== variants ===')
for label, pal in VARIANTS:
    report(label, pal)

os.makedirs(OUT, exist_ok=True)
try:
    f13 = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 13)
    f16 = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 16)
except Exception:
    f13 = f16 = ImageFont.load_default()

SW, SH, GAPX = 70, 56, 6
rows = [('BEFORE (borrowed)', OLD)] + VARIANTS + [('STATUS (locked)', [STATUS[k] for k in STATUS])]
W = 20 + SW * 8 + GAPX * 7 + 20
H = 16 + sum(26 + SH + 38 for _ in rows)
img = Image.new('RGB', (W, H), (255, 255, 255))
d = ImageDraw.Draw(img)
y = 16
for label, pal in rows:
    d.text((20, y), label, fill=(0, 0, 0), font=f16)
    y += 26
    for i, c in enumerate(pal):
        x = 20 + i * (SW + GAPX)
        d.rectangle([x, y, x + SW, y + SH], fill=hex2rgb(c))
        d.text((x + 3, y + SH + 3), c, fill=(90, 90, 90), font=f13)
    y += SH + 38
out = os.path.join(OUT, 'variants.png')
img.save(out)
print('\nswatch: ' + out)
