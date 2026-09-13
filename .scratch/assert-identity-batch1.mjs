/**
 * 批次 1（身份/职能语义改名）真机验收探针。
 * 断言：① 职能判定取代 userType 判定后，导航/首页/标题按职能分流；
 *       ② 多职能账号（worker02 = WORKER + REPORTER）导航取并集；
 *       ③ 注册页能表达「身份 + 教工条线」，且不再出现旧角色码文案。
 * 用法: node .scratch/assert-identity-batch1.mjs   （需 Edge 9222 + 后端 8080 + 前端 3000）
 */
import { connectCdp } from './cdp-raw.mjs';

const ORIGIN = 'http://localhost:3000';
const BACKEND = 'http://localhost:8080/api/v1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let fail = 0;
const check = (name, ok, detail = '') => {
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

const browser = await connectCdp();
const { targetId } = await browser.send('Target.createTarget', { url: 'about:blank', background: true });
const { sessionId } = await browser.send('Target.attachToTarget', { targetId, flatten: true });
const cdp = (m, p = {}) => browser.send(m, p, sessionId);
const evalJs = async (e) => (await cdp('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true })).result?.value;

await cdp('Page.enable');
await cdp('Runtime.enable');
await cdp('Emulation.setDeviceMetricsOverride', { width: 1272, height: 900, deviceScaleFactor: 1, mobile: false });

async function loginAs(username) {
  const res = await (await fetch(`${BACKEND}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'password' })
  })).json();
  return res.data;
}

/** 注入登录态并打开指定路径，返回该页的快照 */
async function open(path, auth) {
  await cdp('Page.navigate', { url: `${ORIGIN}/login` });
  await sleep(1200);
  await evalJs(`(()=>{localStorage.setItem('access_token',${JSON.stringify(auth.accessToken)});localStorage.setItem('refresh_token',${JSON.stringify(auth.refreshToken)});localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(auth.userInfo))});return 1})()`);
  await cdp('Page.navigate', { url: ORIGIN + path });
  await sleep(4500);
  for (let i = 0; i < 25; i++) {
    const ready = await evalJs(`!!document.querySelector('.nav-item') || !!document.querySelector('.register-card')`);
    if (ready) break;
    await sleep(1000);
  }
  return evalJs(`(() => ({
    path: location.pathname,
    title: document.querySelector('.page-title')?.innerText?.trim() || '',
    nav: [...document.querySelectorAll('.nav-item .nav-label')].map(n => n.innerText.trim()),
    roleChip: document.querySelector('.user-role')?.innerText?.trim() || '',
    rawLegacyCodes: (document.body.innerText.match(/USER_STUDENT|USER_TEACHER|REPAIR_WORKER|LOGISTICS_ADMIN|SUPER_ADMIN/g) || []),
    registerRadios: [...document.querySelectorAll('.register-form .el-radio__label')].map(r => r.innerText.trim())
  }))()`);
}

// ---------------- 1) 多职能账号：worker02 = WORKER + REPORTER ----------------
const w2 = await loginAs('worker02');
console.log(`\n[worker02] roleCodes=${w2.userInfo.roleCodes.join(',')} branch=${w2.userInfo.userType}`);
let snap = await open('/', w2);
check('worker02 首页被分流到工作台（存在 WORKER 职能时优先作业台）', snap.path === '/worker/workbench', `实际 ${snap.path}`);
check('worker02 导航取并集：含「我的工作台」', snap.nav.includes('我的工作台'), JSON.stringify(snap.nav));
check('worker02 导航取并集：含「我的工单」（兼职报修人）', snap.nav.includes('我的工单'), JSON.stringify(snap.nav));
check('worker02 导航含「提交报修」', snap.nav.includes('提交报修'), JSON.stringify(snap.nav));
check('worker02 不出现管理端导航（无 DISPATCHER）', !snap.nav.includes('后勤调度中心'), JSON.stringify(snap.nav));
check('worker02 顶栏职能称号为中文多值', snap.roleChip === '维修作业 · 报修人', snap.roleChip);
check('worker02 页面无旧角色码残留', snap.rawLegacyCodes.length === 0, JSON.stringify(snap.rawLegacyCodes));

// ---------------- 2) 单一职能对照：worker03 = WORKER ----------------
const w3 = await loginAs('worker03');
console.log(`\n[worker03] roleCodes=${w3.userInfo.roleCodes.join(',')}`);
snap = await open('/', w3);
check('worker03 导航只有师傅项（无「我的工单」）', !snap.nav.includes('我的工单') && snap.nav.includes('我的工作台'), JSON.stringify(snap.nav));
check('worker03 顶栏称号为「维修作业」', snap.roleChip === '维修作业', snap.roleChip);

// ---------------- 3) 教工：FACULTY + REPORTER ----------------
const t1 = await loginAs('teacher01');
console.log(`\n[teacher01] roleCodes=${t1.userInfo.roleCodes.join(',')} branch=${t1.userInfo.userType}`);
snap = await open('/', t1);
check('teacher01 首页为「我的工单」', snap.path === '/' && snap.title === '我的工单', `${snap.path} / ${snap.title}`);
check('teacher01 导航为报修人两项', JSON.stringify(snap.nav) === JSON.stringify(['提交报修', '我的工单']), JSON.stringify(snap.nav));
check('teacher01 无管理端导航', !snap.nav.includes('用户台账管理'), JSON.stringify(snap.nav));
check('teacher01 页面无旧角色码残留', snap.rawLegacyCodes.length === 0, JSON.stringify(snap.rawLegacyCodes));

// ---------------- 4) 管理员：PLATFORM_ADMIN ----------------
const adm = await loginAs('admin');
console.log(`\n[admin] roleCodes=${adm.userInfo.roleCodes.join(',')} branch=${adm.userInfo.userType}`);
snap = await open('/', adm);
check('admin 首页为控制台概览', snap.title === '控制台概览', snap.title);
check('admin 导航 6 项且含用户台账', snap.nav.length === 6 && snap.nav.includes('用户台账管理'), JSON.stringify(snap.nav));
check('admin 顶栏称号为「平台管理」', snap.roleChip === '平台管理', snap.roleChip);

// ---------------- 5) 越权：教工访问管理页应落 403 ----------------
snap = await open('/system/users', t1);
check('教工访问 /system/users 落 403', snap.path === '/403', snap.path);
check('403 页显示职能而非旧角色码', !snap.rawLegacyCodes.length, JSON.stringify(snap.rawLegacyCodes));

// ---------------- 6) 注册页：身份 + 教工条线 ----------------
await cdp('Page.navigate', { url: `${ORIGIN}/register` });
await sleep(3500);
let reg = await evalJs(`(() => ({
  radios: [...document.querySelectorAll('.register-form .el-radio__label')].map(r => r.innerText.trim()),
  legacy: (document.body.innerText.match(/USER_STUDENT|USER_TEACHER|SUPER_ADMIN/g) || [])
}))()`);
check('注册页身份选项为「在校学生 / 教职员工」', reg.radios.includes('在校学生') && reg.radios.includes('教职员工'), JSON.stringify(reg.radios));
check('注册页学生身份下不出现条线选择', !reg.radios.some(t => t.includes('教学科研')), JSON.stringify(reg.radios));

// 切到教工身份后应出现条线二选一
await evalJs(`(() => {
  const labels = [...document.querySelectorAll('.register-form .el-radio')];
  const staff = labels.find(l => l.innerText.includes('教职员工'));
  staff?.querySelector('input')?.click();
  return 1;
})()`);
await sleep(1200);
reg = await evalJs(`(() => [...document.querySelectorAll('.register-form .el-radio__label')].map(r => r.innerText.trim()))()`);
check('教工身份下出现条线二选一（教学科研 / 后勤保障）',
  reg.some(t => t.includes('教学科研')) && reg.some(t => t.includes('后勤保障')), JSON.stringify(reg));
check('注册页不出现旧角色码文案', !reg.some(t => /USER_|SUPER_ADMIN|REPAIR_WORKER/.test(t)), JSON.stringify(reg));

console.log(`\n${fail === 0 ? 'ALL PASS' : fail + ' FAILED'}`);
await browser.send('Target.closeTarget', { targetId });
browser.close();
process.exit(fail === 0 ? 0 : 1);
