/**
 * el-button 全变体几何测量（落样式层之前/之后的同一把尺子）。
 *
 * 做法：**往页面里注入一整套 EP 按钮变体再量**，而不是逐页去找按钮——
 * 变体的样式全部来自全局 EP CSS，量一次即可覆盖；注入的好处是注入前后用的是同一把尺子，
 * 可以逐值对比，且不依赖任何路由/权限。
 *
 * 用法: node .scratch/probe-button-metrics.mjs
 */
const PROXY = process.env.BS_CDP_PROXY || 'http://127.0.0.1:3456';
const ORIGIN = 'http://localhost:3000';

const proxyPost = async (path, body) => {
  const res = await fetch(`${PROXY}${path}`, { method: 'POST', body });
  const json = await res.json();
  if (json.error) throw new Error(`${path} → ${json.error}`);
  return json;
};

const health = await (await fetch(`${PROXY}/health`)).json().catch(() => null);
if (!health?.connected) {
  console.log(`代理 ${PROXY} 未就绪`);
  process.exit(2);
}

const { targetId } = await proxyPost('/new', `${ORIGIN}/login`);
const evalJs = async (expr) => (await proxyPost(`/eval?target=${targetId}`, expr)).value;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
await sleep(2500);

const SCRIPT = `(() => {
  document.getElementById('btn-probe')?.remove();
  const box = document.createElement('div');
  box.id = 'btn-probe';
  box.style.cssText = 'position:fixed;left:-9999px;top:0';
  box.innerHTML = [
    '<button class="el-button" id="b-default">默认按钮</button>',
    '<button class="el-button el-button--primary" id="b-primary">主要</button>',
    '<button class="el-button el-button--small" id="b-small">小</button>',
    '<button class="el-button el-button--large" id="b-large">大</button>',
    '<button class="el-button el-button--primary is-link" id="b-link">链接</button>',
    '<button class="el-button el-button--small is-link" id="b-link-small">小链接</button>',
    '<button class="el-button is-circle" id="b-circle">圆</button>',
    '<button class="el-button el-button--small is-circle" id="b-circle-small">小圆</button>',
    '<button class="el-button el-button--success is-plain" id="b-plain">朴素</button>',
    '<button class="el-button el-button--small is-plain" id="b-plain-small">小朴素</button>',
    '<button class="el-button is-disabled" id="b-disabled">禁用</button>',
    '<button class="el-button el-button--primary is-disabled" id="b-primary-disabled">主禁用</button>',
    '<button class="el-button el-button--primary is-loading" id="b-loading">载入中</button>',
    '<button class="el-button el-button--small is-disabled" id="b-small-disabled">小禁用</button>',
    '<button class="el-button el-button--primary is-round" id="b-round">圆角</button>',
    '<button class="el-button el-button--danger is-text" id="b-text">文字</button>'
  ].join('');
  document.body.appendChild(box);
  const rows = [];
  for (const el of box.querySelectorAll('button')) {
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    rows.push({
      id: el.id,
      h: Math.round(r.height * 100) / 100,
      w: Math.round(r.width * 100) / 100,
      padL: cs.paddingLeft, padR: cs.paddingRight,
      radius: cs.borderTopLeftRadius,
      borderW: cs.borderTopWidth,
      fontSize: cs.fontSize, fontWeight: cs.fontWeight,
      color: cs.color, bg: cs.backgroundColor,
      lineHeight: cs.lineHeight
    });
  }
  return JSON.stringify(rows);
})()`;

const rows = JSON.parse(await evalJs(SCRIPT));
console.log('id                 高    宽      pad        radius    border  字号  字重  颜色');
for (const r of rows) {
  console.log(
    `${r.id.padEnd(18)} ${String(r.h).padStart(5)} ${String(r.w).padStart(6)}  ` +
    `${(r.padL + '/' + r.padR).padEnd(11)} ${r.radius.padEnd(9)} ${r.borderW.padEnd(7)} ` +
    `${r.fontSize.padEnd(5)} ${r.fontWeight.padEnd(5)} ${r.color}`
  );
}
await fetch(`${PROXY}/close?target=${targetId}`).catch(() => {});
