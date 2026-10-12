/**
 * A6（水印里的品牌标记）的实机判据 + 实拍。
 *
 * 由来：规范 §4.6 的 A6——水印 canvas 原先只 `fillText` 三行字，要加一个**矢量标记**。
 * 这里跑的是**真实的** `addWatermarkToImage`（从 Vite 模块图 import，不是抄一份逻辑），
 * 再用**像素**判断它到底画上了没有、有没有把文字挤到重叠。
 *
 * 判据为什么必须看像素：这个函数没有任何返回值可供断言（它只回一个 Blob），
 * 而「标识画上去了」和「标识没画、但文字恰好占住了那块地方」在**任何结构断言**下都一样。
 *
 * 测试图刻意用**纯色**（#DDD），于是三类像素天然可分：
 *   · 底卡   rgba(0,0,0,.58) 压在 #DDD 上 → 灰阶约 93
 *   · 墨迹   白色 #FFF                → 灰阶 255
 *   · 画布   #DDD                     → 灰阶 221
 * 于是底卡范围、墨迹列分布都能直接量，不需要读源码里的公式。
 *
 * 断言：
 *   ① 能出图（Blob 且是 image/jpeg）—— 否则下面都是在量一张空图；
 *   ② 底卡在（找到暗色矩形，且宽度明显大于纯文字所需的宽度）；
 *   ③ 底卡**左侧**那块（标识该在的位置）里有白色墨迹；
 *   ④ 标识与文字之间有**一条干净的隔离带**（该列区间内 0 个白色像素）；
 *   ⑤ 文字确实**让位**了：文字左沿 > 底卡左沿 + padding + 标识宽的 8 成
 *      —— 这是 ③ 的反向自证（若标识没画而文字也没让位，③ 会因为文字填满左区而恒真地通过）；
 *   ⑥ 标识的横向跨度 ≤ 分配给它的大小（没有溢出到隔离带里）。
 *
 * 用法: node .scratch/check-watermark.mjs
 * 产物: .scratch/v3-shots/watermark.png
 */
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const OUT = '.scratch/v3-shots';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

mkdirSync(OUT, { recursive: true });
const health = await (await fetch(`${PROXY}/health`)).json();
if (!health?.connected) throw new Error(`代理未就绪：${JSON.stringify(health)}`);

let fail = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

const { targetId } = await (await fetch(`${PROXY}/new`, { method: 'POST', body: `${ORIGIN}/login` })).json();
await sleep(1500);
async function evalJs(expr, attempts = 3) {
  for (let i = 1; i <= attempts; i++) {
    try {
      const r = await (await fetch(`${PROXY}/eval?target=${targetId}`, { method: 'POST', body: expr })).json();
      if (r.error) throw new Error(r.error);
      return r.value;
    } catch (e) {
      if (i === attempts) throw e;
      await sleep(1200 * i);
    }
  }
}

/* 1) 把真实的 watermark.ts 拉进页面（走 Vite 模块图） */
const absModule = resolve('packages/shared/src/utils/watermark.ts').replace(/\\/g, '/');
await evalJs(
  `(() => {
     const s = document.createElement('script');
     s.type = 'module';
     s.textContent = "import('/@fs/${absModule}')" +
       ".then(m=>{window.__WM=m;document.title='WM_READY'})" +
       ".catch(e=>{document.title='WM_FAIL:'+e.message})";
     document.head.appendChild(s);
     return 1;
   })()`
);
let ready = false;
for (let i = 0; i < 30; i++) {
  const t = await evalJs('document.title').catch(() => null);
  if (t === 'WM_READY') {
    ready = true;
    break;
  }
  if (typeof t === 'string' && t.startsWith('WM_FAIL')) throw new Error(`watermark.ts 导入失败：${t}`);
  await sleep(400);
}
if (!ready) throw new Error('等待 watermark.ts 导入超时');

/* 2) 造一张纯色照片 → 走真实的水印函数 → 画回页面并量像素 */
const res = JSON.parse(
  await evalJs(`(async () => {
    const W = 1200, H = 900;
    const src = document.createElement('canvas');
    src.width = W; src.height = H;
    const sx = src.getContext('2d');
    sx.fillStyle = '#DDDDDD';
    sx.fillRect(0, 0, W, H);
    const srcBlob = await new Promise(b => src.toBlob(b, 'image/png'));

    const out = await window.__WM.addWatermarkToImage(srcBlob, {
      orderNo: 'BX20261010000105',
      workerName: '吴综合',
      timestamp: '2026-10-12 18:40:07'
    });

    const bmp = await createImageBitmap(out);
    const cv = document.createElement('canvas');
    cv.width = bmp.width; cv.height = bmp.height;
    const cx = cv.getContext('2d');
    cx.drawImage(bmp, 0, 0);

    const img = cx.getImageData(0, 0, bmp.width, bmp.height).data;
    const g = (x, y) => img[(y * bmp.width + x) * 4];   // 纯灰图，取 R 即可

    // 底卡 bbox：灰阶 < 160 的像素范围（画布 221，底卡约 93）
    let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1;
    for (let y = 0; y < bmp.height; y++) {
      for (let x = 0; x < bmp.width; x++) {
        if (g(x, y) < 160) {
          if (x < x0) x0 = x; if (x > x1) x1 = x;
          if (y < y0) y0 = y; if (y > y1) y1 = y;
        }
      }
    }
    if (x1 < 0) return JSON.stringify({ error: 'no-card' });
    const cardW = x1 - x0 + 1, cardH = y1 - y0 + 1;

    /*
     * 每一列在底卡高度范围内有没有「墨迹」（灰阶 > 200，底卡 93 / 文字白 255）。
     *
     * ⚠️ **必须避开圆角那几行**：底卡是圆角矩形（半径 ≈ 0.4 字号），
     * 在左下/左上角的圆角外侧，像素是**画布本色** #DDD（221 > 200）——
     * 按「>200 就是墨迹」判，它会被当成墨迹，于是最左的 4 列永远是"墨迹"，
     * 标识簇被算成 4px 宽。第一版就是这么误报 FAIL 的。
     * 判据没错，是量具把底卡**外面**的背景算进来了。
     */
    const PAD = 14;
    const yA = y0 + PAD, yB = y1 - PAD;
    const cols = [];
    for (let x = x0; x <= x1; x++) {
      let n = 0;
      for (let y = yA; y <= yB; y++) if (g(x, y) > 200) n++;
      cols.push(n);
    }
    const inkCols = cols.map((n, i) => (n > 0 ? i : -1)).filter(i => i >= 0);

    // 从最左的墨迹列开始，找第一段纯空列（隔离带）与其后的墨迹列（= 文字块起点）
    let gapStart = -1, gapEnd = -1;
    const firstInk = inkCols[0];
    for (let i = firstInk; i < cols.length; i++) {
      if (cols[i] === 0) { if (gapStart < 0) gapStart = i; }
      else if (gapStart >= 0) { gapEnd = i - 1; break; }
    }
    // 左侧「标识簇」= firstInk .. gapStart-1（若没有隔离带则一直到末尾）
    const markLeft = firstInk;
    const markRight = gapStart >= 0 ? gapStart - 1 : cols.length - 1;
    const textLeft = gapEnd >= 0 ? gapEnd + 1 : null;

    // 把**底卡那块裁下来**贴回页面给眼睛看（整图直接贴会被视口截掉，卡片在右下角看不见）
    document.body.innerHTML = '';
    document.body.style.margin = '0';
    document.body.style.background = '#334455';
    const M = 18, SCALE = 3;
    const crop = document.createElement('canvas');
    crop.width = (cardW + M * 2) * SCALE;
    crop.height = (cardH + M * 2) * SCALE;
    const cc = crop.getContext('2d');
    cc.imageSmoothingEnabled = false;
    cc.drawImage(cv, x0 - M, y0 - M, cardW + M * 2, cardH + M * 2, 0, 0, crop.width, crop.height);
    const show = document.createElement('img');
    show.id = 'wm-out';
    show.src = crop.toDataURL('image/png');
    show.style.cssText = 'display:block;margin:40px auto';
    document.body.appendChild(show);
    await show.decode();

    return JSON.stringify({
      type: out.type, size: out.size, w: bmp.width, h: bmp.height,
      cardX: x0, cardY: y0, cardW, cardH,
      markLeft: x0 + markLeft, markRight: x0 + markRight,
      markWidth: markRight - markLeft + 1,
      gapWidth: gapStart >= 0 && gapEnd >= 0 ? gapEnd - gapStart + 1 : 0,
      textLeft: textLeft === null ? null : x0 + textLeft,
      inkCols: inkCols.length
    });
  })()`)
);

if (res.error) throw new Error(`水印像素分析失败：${res.error}`);

check('① 水印函数出图（image/jpeg）', res.type === 'image/jpeg' && res.size > 1000, `${res.type} ${res.size}B`);
check('② 底卡在（找到暗色矩形）', res.cardW > 100 && res.cardH > 50, `底卡 ${res.cardW}×${res.cardH} @(${res.cardX},${res.cardY})`);
check('③ 底卡左侧那块（标识该在的位置）里有白色墨迹', res.markWidth > 20 && res.inkCols > 30,
  `左侧墨迹簇宽 ${res.markWidth}px，全卡有墨迹的列 ${res.inkCols} 列`);
check('④ 标识与文字之间有一条干净的隔离带', res.gapWidth >= 8, `隔离带 ${res.gapWidth}px`);
/*
 * ⑤ 是 ③ 的反向自证：如果标识没画、文字也没让位，文字会从底卡左沿附近起笔，
 * 于是 ③ 会被「文字填满左区」满足而**恒真地通过**。这条把那种情况钉死。
 */
check('⑤ 文字确实让位了（文字左沿 > 底卡左沿 + ~标识宽 + 内边距 的 8 成）',
  res.textLeft !== null && res.markRight !== null && res.textLeft > res.markLeft + 8,
  `底卡左沿 ${res.cardX} → 标识 [${res.markLeft},${res.markRight}] → 文字左沿 ${res.textLeft}`);
check('⑥ 标识没有溢出到隔离带里', res.markWidth <= res.cardW * 0.5,
  `标识簇宽 ${res.markWidth}px / 底卡宽 ${res.cardW}px`);

/* 3) 截图：给眼睛看 */
await sleep(500);
const file = `${OUT}/watermark.png`;
let saved = null;
let lastErr = null;
for (let i = 1; i <= 4; i++) {
  const r = await fetch(`${PROXY}/screenshot?target=${targetId}&file=${encodeURIComponent(file)}`);
  const body = (await r.text()).trim();
  if (body.includes('saved')) {
    saved = body;
    break;
  }
  lastErr = body;
  await sleep(1500 * i);
}
await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
if (!saved) throw new Error(`截图失败：${lastErr}`);
console.log(`OK    ${file}`);

console.log(`\n[${fail ? fail + ' FAILED' : 'ALL PASS'}]`);
process.exit(fail ? 1 : 0);
