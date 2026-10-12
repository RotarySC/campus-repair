/** 对齐自检：剥注释后的行号必须与源文件行号一一对应 */
import { readFileSync } from 'node:fs';
import { execSync } from 'node:child_process';

function codeOf(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/^[^\S\n]*\/\/[^\n]*/gm, ' ')
    .replace(/[^\S\n]\/\/[^\S\n][^\n]*/gm, ' ');
}

const files = execSync('git ls-files apps/admin-web/src', { encoding: 'utf8' })
  .split('\n')
  .filter((f) => /\.(vue|ts|css)$/.test(f));

let bad = 0;
for (const f of files) {
  const raw = readFileSync(f, 'utf8');
  const stripped = codeOf(raw);
  const rl = raw.split('\n');
  const sl = stripped.split('\n');
  if (rl.length !== sl.length) {
    console.log(`${f}: 行数 ${rl.length} -> ${sl.length}`);
    bad++;
    continue;
  }
  // 逐行核对：stripped 的每一行，要么是 raw 同一行剥掉注释的结果，要么行号一定错位
  const HEX = /#[0-9A-Fa-f]{3,8}\b/;
  for (let i = 0; i < rl.length; i++) {
    if (HEX.test(sl[i]) && !HEX.test(rl[i])) {
      console.log(`${f}:${i + 1} 剥注释后有色值、原行没有`);
      console.log(`   raw: ${JSON.stringify(rl[i])}`);
      console.log(`   str: ${JSON.stringify(sl[i])}`);
      bad++;
      break;
    }
  }
}
console.log(bad ? `\n${bad} 个文件行号不可靠` : '\n逐行对齐：全部文件 OK');
