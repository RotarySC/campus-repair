/**
 * 生成 PWA 主屏图标（A5）——**位图导出的唯一配方**，入库备查。
 *
 * 为什么走浏览器：`manifest` 图标必须是 PNG，而仓库里没有任何图像库
 * （没有 sharp / resvg / canvas-native，也不该为了 5 个静态图标引一个带原生依赖的包）。
 * 手边已经有一个真浏览器（CDP 代理），SVG → `<img>` → `<canvas>` → `toDataURL('image/png')`
 * 就是一条零依赖、且与最终显示环境**同一个渲染引擎**的栅格化通路。
 *
 * ⚠️ **路径数据从模块里拿，不在本脚本里抄一份**：
 * `import('/@fs/<abs>/packages/shared/src/constants/brand.ts')` 取的是 Vite 正在服务的那份源码。
 * 抄一份的话，改了 `brand.ts` 而忘了重跑本脚本，图标就会**悄悄停在旧几何上**
 * ——三处品牌标识长得不一样，是最难被发现的一类缺陷。
 *
 * 用法: node .scratch/make-pwa-icons.mjs
 * 产物: apps/admin-web/public/{pwa-192,pwa-512,pwa-maskable-192,pwa-maskable-512,apple-touch-icon}.png
 */
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';
const PUB = 'apps/admin-web/public';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* 图标几何：`k` 是「24 号 viewBox 占画布的几分之几」。
   · any 档 0.72 —— 描边外扩之后可见图形约 64% 宽，接近主屏图标惯用比例
   · maskable 档 0.60 —— 必须整个落在**内切 80% 圆**里（那个圆是要被裁掉的边界），
     图形对角线 k×27.05，取 0.60 时半对角线约 0.34S < 0.4S，留够余量
   背景一律 #FFFFFF（tokens 的 --surface）。**不用 prefers-color-scheme**：
   PNG 是静态的，manifest 图标没有媒体查询可用。 */
const BG = '#FFFFFF';
const INK = '#1A1A1A';
const TARGETS = [
  { file: 'pwa-192.png', size: 192, k: 0.72 },
  { file: 'pwa-512.png', size: 512, k: 0.72 },
  { file: 'pwa-maskable-192.png', size: 192, k: 0.6 },
  { file: 'pwa-maskable-512.png', size: 512, k: 0.6 },
  { file: 'apple-touch-icon.png', size: 180, k: 0.72 }
];

const health = await (await fetch(`${PROXY}/health`)).json();
if (!health?.connected) throw new Error(`代理未就绪：${JSON.stringify(health)}`);

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

/* 1) 把 brand.ts 里的真实几何取进页面（走 Vite 的模块图，不抄一份） */
const absModule = resolve('packages/shared/src/constants/brand.ts').replace(/\\/g, '/');
await evalJs(
  `(() => {
     const s = document.createElement('script');
     s.type = 'module';
     s.textContent = "import('/@fs/${absModule}')" +
       ".then(m=>{window.__BRAND=m;document.title='BRAND_READY'})" +
       ".catch(e=>{document.title='BRAND_FAIL:'+e.message})";
     document.head.appendChild(s);
     return 1;
   })()`
);
let ready = false;
for (let i = 0; i < 30; i++) {
  const t = await evalJs('document.title').catch(() => null);
  if (t === 'BRAND_READY') {
    ready = true;
    break;
  }
  if (typeof t === 'string' && t.startsWith('BRAND_FAIL')) throw new Error(`brand.ts 导入失败：${t}`);
  await sleep(400);
}
if (!ready) throw new Error('等待 brand.ts 导入超时');

const geom = JSON.parse(
  await evalJs(
    `JSON.stringify({
       paths: window.__BRAND.BRAND_MARK_PATHS,
       viewBox: window.__BRAND.BRAND_MARK_VIEWBOX,
       stroke: window.__BRAND.BRAND_MARK_STROKE_WIDTH
     })`
  )
);
console.log(`INFO  brand.ts: viewBox=${geom.viewBox} stroke=${geom.stroke} paths=${geom.paths.length}`);

/* 2) 逐尺寸栅格化 */
for (const t of TARGETS) {
  const b64 = await evalJs(`(async () => {
    const S = ${t.size}, K = ${t.k};
    const k = K * S / ${geom.viewBox};
    const off = (S - ${geom.viewBox} * k) / 2;
    const svg =
      '<svg xmlns="http://www.w3.org/2000/svg" width="' + S + '" height="' + S + '" viewBox="0 0 ' + S + ' ' + S + '">' +
      '<rect width="' + S + '" height="' + S + '" fill="${BG}"/>' +
      '<g transform="translate(' + off + ',' + off + ') scale(' + k + ')" fill="none" stroke="${INK}"' +
      ' stroke-width="${geom.stroke}" stroke-linecap="square" stroke-linejoin="miter">' +
      ${JSON.stringify(geom.paths)}.map(d => '<path d="' + d + '"/>').join('') +
      '</g></svg>';
    const img = new Image();
    img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
    await img.decode();
    const cv = document.createElement('canvas');
    cv.width = S; cv.height = S;
    const cx = cv.getContext('2d');
    cx.drawImage(img, 0, 0, S, S);
    // 自证：确认真的画上了东西（全白画布是真有可能的 —— 比如 SVG 没解出来）
    const d = cx.getImageData(0, 0, S, S).data;
    let ink = 0;
    for (let i = 0; i < d.length; i += 4) if (d[i] < 200) ink++;
    if (ink < S) throw new Error('画布上几乎没有墨色像素：' + ink);
    window.__inkRatio = +(ink / (S * S)).toFixed(4);
    return cv.toDataURL('image/png').split(',')[1];
  })()`);
  const out = `${PUB}/${t.file}`;
  writeFileSync(out, Buffer.from(b64, 'base64'));
  const ratio = await evalJs('window.__inkRatio');
  const bytes = Buffer.from(b64, 'base64').length;
  console.log(`OK    ${out}  ${t.size}×${t.size}  k=${t.k}  墨色占比 ${(ratio * 100).toFixed(1)}%  ${(bytes / 1024).toFixed(1)} KB`);
}

await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
