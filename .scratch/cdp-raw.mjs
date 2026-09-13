/**
 * 极简 CDP 客户端（raw socket 实现 WebSocket 握手/帧解析）。
 * 背景：本机 Node 24 内置 WebSocket 连 ws://127.0.0.1:9222/devtools/browser 既不 open 也不 error
 * （挂住，curl 手工握手同一 URL 返回 101），且仓库里没有 ws 包，故这里自带一个最小实现。
 * 仅用于诊断脚本，不替代 .scratch 下既有探针。
 */
import net from 'node:net';
import crypto from 'node:crypto';

export function connectCdp(port = 9222, host = '127.0.0.1', path = '/devtools/browser') {
  return new Promise((resolve, reject) => {
    const sock = net.connect(port, host);
    const key = crypto.randomBytes(16).toString('base64');
    let handshakeDone = false;
    let buf = Buffer.alloc(0);
    const waiters = new Map();
    let seq = 0;

    const handlers = { message: () => {} };

    const sendFrame = (str) => {
      const payload = Buffer.from(str, 'utf8');
      const len = payload.length;
      let header;
      const mask = crypto.randomBytes(4);
      if (len < 126) {
        header = Buffer.alloc(6);
        header[0] = 0x81; header[1] = 0x80 | len; mask.copy(header, 2);
      } else if (len < 65536) {
        header = Buffer.alloc(8);
        header[0] = 0x81; header[1] = 0x80 | 126; header.writeUInt16BE(len, 2); mask.copy(header, 4);
      } else {
        header = Buffer.alloc(14);
        header[0] = 0x81; header[1] = 0x80 | 127; header.writeBigUInt64BE(BigInt(len), 2); mask.copy(header, 10);
      }
      const masked = Buffer.alloc(len);
      for (let i = 0; i < len; i++) masked[i] = payload[i] ^ mask[i % 4];
      sock.write(Buffer.concat([header, masked]));
    };

    const pong = (payload) => {
      const h = Buffer.alloc(6);
      h[0] = 0x8a; h[1] = 0x80 | payload.length;
      const mask = crypto.randomBytes(4); mask.copy(h, 2);
      const m = Buffer.alloc(payload.length);
      for (let i = 0; i < payload.length; i++) m[i] = payload[i] ^ mask[i % 4];
      sock.write(Buffer.concat([h, m]));
    };

    let frag = [];
    let fragOp = 0;

    const drain = () => {
      while (buf.length >= 2) {
        const b0 = buf[0], b1 = buf[1];
        const fin = (b0 & 0x80) !== 0, op = b0 & 0x0f;
        const masked = (b1 & 0x80) !== 0;
        let len = b1 & 0x7f, off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        if (masked) off += 4;
        if (buf.length < off + len) return;
        const payload = buf.subarray(off, off + len);
        buf = buf.subarray(off + len);
        if (op === 0x9) { pong(payload); continue; }
        if (op === 0xa || op === 0x8) { if (op === 0x8) sock.end(); continue; }
        if (op === 0x0) frag.push(payload);
        else { frag = [payload]; fragOp = op; }
        if (!fin) continue;
        if (fragOp === 0x1) {
          const text = Buffer.concat(frag).toString('utf8');
          frag = [];
          const m = JSON.parse(text);
          if (m.id && waiters.has(m.id)) {
            const w = waiters.get(m.id); waiters.delete(m.id);
            m.error ? w.rej(new Error(JSON.stringify(m.error))) : w.res(m.result);
          } else handlers.message(m);
        }
      }
    };

    const onData = (chunk) => {
      if (!handshakeDone) {
        buf = Buffer.concat([buf, chunk]);
        const idx = buf.indexOf('\r\n\r\n');
        if (idx < 0) return;
        const head = buf.subarray(0, idx).toString('utf8');
        buf = buf.subarray(idx + 4);
        if (!/HTTP\/1\.1 101/.test(head)) return reject(new Error('handshake failed: ' + head.split('\r\n')[0]));
        handshakeDone = true;
        resolve(api);
        drain();
        return;
      }
      buf = Buffer.concat([buf, chunk]);
      drain();
    };

    const api = {
      send(method, params = {}, sessionId) {
        const id = ++seq;
        const msg = { id, method, params };
        if (sessionId) msg.sessionId = sessionId;
        return new Promise((res, rej) => {
          waiters.set(id, { res, rej });
          sendFrame(JSON.stringify(msg));
          setTimeout(() => { if (waiters.has(id)) { waiters.delete(id); rej(new Error('timeout ' + method)); } }, 30000);
        });
      },
      onMessage(fn) { handlers.message = fn; },
      close() { try { sock.end(); } catch {} }
    };

    sock.on('data', onData);
    sock.on('error', (e) => { if (!handshakeDone) reject(e); });
    sock.once('connect', () => {
      sock.write(
        `GET ${path} HTTP/1.1\r\n` +
        `Host: ${host}:${port}\r\n` +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        `Sec-WebSocket-Key: ${key}\r\n` +
        'Sec-WebSocket-Version: 13\r\n\r\n'
      );
    });
  });
}
