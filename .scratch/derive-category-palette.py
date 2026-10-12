#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""推导分类色板（方案甲）—— 把「等明度、等彩度、色相均分」落到具体色号上。

三个参数：明度 L*、彩度 C*、色相起始角 offset。前两个是设计取舍，第三个**可以算**：

  · 7 个状态色把色轮切成 7 段，8 个分类色相均分（每 45°）**不可能全部躲开**；
  · 但起始角可以选 —— 让「离最近的状态色」这个量**最大化**。
    这是一个单参数搜索，没有理由靠手感定。

另两个取舍，都有理由：

  · **等明度**：分类之间没有轻重之分。改前 L* 极差 38.1，紫比琥珀重一倍。
    定在 L*≈60 —— 白底上不刺眼、也不至于浅到发飘。
  · **C*≈46，比状态色低一档**：D 的规矩是「色相只服务状态语义」。
    分类色要跟状态色**同时出现在一屏上**（控制台就有状态圆环），
    所以分类必须整体**比状态安静**，否则"谁更重要"就又乱了。
    实测状态色的 C* 在 40~95，取 46 落在它的下沿。

用法: python .scratch/derive-category-palette.py
产物: .superpowers/palette/category-palette.png（对照图）
"""
import math
import os

from PIL import Image, ImageDraw, ImageFont

BS = r"C:\Users\Rotary\Documents\Comprehensive\Files\Projects\BS"
OUT = os.path.join(BS, ".superpowers", "palette")

# 改前（借来的那套）——迁移后它必须彻底消失
OLD = ['#1677FF', '#52C41A', '#FAAD14', '#FA541C', '#722ED1', '#13C2C2', '#EB2F96', '#8C8C8C']
STATUS = {
    'h1': '#FA8C16', 'h2': '#13C2C2', 'h3': '#1677FF', 'h4': '#6B7C93',
    'h5': '#722ED1', 'h6': '#F5222D', 'h7': '#52C41A', 'h8': '#B3B3B3',
}
STATUS_CHROMATIC = ['h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'h7']

L_FIX, C_FIX, N = 60.0, 46.0, 8


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


def lab2hex(L, a, b):
    """Lab -> sRGB hex；越界就夹回 0..255（本参数域内不会越界，留个保险）"""
    fy = (L + 16) / 116
    fx, fz = fy + a / 500, fy - b / 200
    g = lambda t: t ** 3 if t ** 3 > 0.008856 else (t - 16 / 116) / 7.787
    X, Y, Z = 0.95047 * g(fx), 1.0 * g(fy), 1.08883 * g(fz)
    r = X * 3.2406 + Y * -1.5372 + Z * -0.4986
    gg = X * -0.9689 + Y * 1.8758 + Z * 0.0415
    bb = X * 0.0557 + Y * -0.2040 + Z * 1.0570
    enc = lambda v: 1.055 * (v ** (1 / 2.4)) - 0.055 if v > 0.0031308 else 12.92 * v
    return '#%02X%02X%02X' % tuple(min(255, max(0, round(enc(v) * 255))) for v in (r, gg, bb))


def build(offset, chroma=C_FIX):
    out = []
    for i in range(N):
        ang = math.radians(offset + i * (360 / N))
        out.append(lab2hex(L_FIX, chroma * math.cos(ang), chroma * math.sin(ang)))
    return out


st = {k: STATUS[k] for k in STATUS_CHROMATIC}
print('=== status hues (chromatic) ===')
for k, v in st.items():
    L, a, b = lab(v)
    print('  %-3s %s  L*=%5.1f  C*=%5.1f  hue=%6.1f deg' % (k, v, L, math.hypot(a, b), (math.degrees(math.atan2(b, a)) + 360) % 360))
print('\n  status chroma range: %.1f .. %.1f' % (min(math.hypot(lab(v)[1], lab(v)[2]) for v in st.values()),
                                                 max(math.hypot(lab(v)[1], lab(v)[2]) for v in st.values())))

print('\n=== step 1: what chroma can sRGB actually hold at L*=60 for EVERY hue? ===')
print('  (cyan/teal clips first; picking C* higher than this makes those slices')
print('   come out less saturated than the rest, i.e. unequal weight again)')


def fits(L, C, ang_deg):
    """Lab -> hex -> Lab 往返：回来还能对上，说明这个 (L,C,hue) 在 sRGB 里存在"""
    a, b = C * math.cos(math.radians(ang_deg)), C * math.sin(math.radians(ang_deg))
    back = lab(lab2hex(L, a, b))
    return math.dist(back, (L, a, b)) < 1.0


C_MAX = 0.0
c = 20.0
while c <= 70.0:
    if all(fits(L_FIX, c, ang) for ang in range(0, 360, 5)):
        C_MAX = c
    c += 0.5
print('  max usable C* over the whole wheel at L*=%.0f: %.1f' % (L_FIX, C_MAX))

# 留 1 的余量：贴着边界取值时四舍五入到 8bit 容易越界
C_USE = round(C_MAX - 1.0, 1)
print('  using C* = %.1f (1.0 margin for the 8-bit rounding)' % C_USE)

print('\n=== step 2: search the hue offset that maximises distance from every status hue ===')
best = None
for off in range(0, 45):
    pal = build(off, C_USE)
    m = min(min(dE(c, s) for s in st.values()) for c in pal)
    if best is None or m > best[0]:
        best = (m, off, pal)
min_to_status, offset, PAL = best
print('  best offset = %d deg   -> min dE to any status hue = %.1f' % (offset, min_to_status))
print('  (an arbitrary offset, e.g. 20 deg, gives %.1f)'
      % min(min(dE(c, s) for s in st.values()) for c in build(20, C_USE)))

# 色板内每个色的实测色度：必须彼此靠得住，否则就是"重量不均"又回来了
off_spec = [(PAL[i], lab(PAL[i]), math.hypot(lab(PAL[i])[1], lab(PAL[i])[2]))
            for i in range(N) if not fits(L_FIX, C_USE, offset + i * 45)]
print('  %d / %d entries still off-target after the gamut fix' % (len(off_spec), N))
for hx, l, ch in off_spec:
    print('    %s  L*=%.1f C*=%.1f' % (hx, l[0], ch))

Ls = [lab(c)[0] for c in PAL]
Cs = [math.hypot(lab(c)[1], lab(c)[2]) for c in PAL]
adj = [dE(PAL[i - 1], PAL[i]) for i in range(1, N)]
mn = min(dE(PAL[i], PAL[j]) for i in range(N) for j in range(i + 1, N))
print('\n=== derived palette ===')
print('  ' + '  '.join(PAL))
print('  L* spread %.1f   C* spread %.1f   adjacent dE min %.1f   all-pairs dE min %.1f'
      % (max(Ls) - min(Ls), max(Cs) - min(Cs), min(adj), mn))
print('  min dE to any status hue: %.1f' % min_to_status)

print('\n=== css lines for tokens.css ===')
for i, c in enumerate(PAL, 1):
    L, a, b = lab(c)
    print('  --cat-%d: %s; /* 色相 %.0f° L*%.1f C*%.1f */' % (i, c.lower(), (math.degrees(math.atan2(b, a)) + 360) % 360, L, math.hypot(a, b)))

print('\n=== reverse self-check: the borrowed values must NOT appear in the new palette ===')
for c in OLD:
    hit = [x for x in PAL if dE(c, x) < 3]
    if hit:
        print('  !! %s still collides with %s' % (c, hit))

# 对照图
os.makedirs(OUT, exist_ok=True)
try:
    f14 = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 14)
    f16 = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 16)
except Exception:
    f14 = f16 = ImageFont.load_default()

SW, SH, GAPX = 76, 60, 6
W, H = 20 + SW * 8 + GAPX * 7 + 20, 300
img = Image.new('RGB', (W, H), (255, 255, 255))
d = ImageDraw.Draw(img)
y = 16
for label, pal in (('BEFORE  (borrowed)', OLD), ('AFTER  (derived)', PAL)):
    d.text((20, y), label, fill=(0, 0, 0), font=f16)
    y += 26
    for i, c in enumerate(pal):
        x = 20 + i * (SW + GAPX)
        d.rectangle([x, y, x + SW, y + SH], fill=hex2rgb(c))
        d.text((x + 4, y + SH + 4), c, fill=(90, 90, 90), font=f14)
    y += SH + 44
d.text((20, y), 'STATUS (locked, must stay the loudest):', fill=(0, 0, 0), font=f16)
y += 26
for i, (k, c) in enumerate(STATUS.items()):
    x = 20 + i * (SW + GAPX)
    d.rectangle([x, y, x + SW, y + SH - 12], fill=hex2rgb(c))
    d.text((x + 4, y + SH - 6), k, fill=(90, 90, 90), font=f14)

out = os.path.join(OUT, 'category-palette.png')
img.save(out)
print('\nswatch: ' + out)
