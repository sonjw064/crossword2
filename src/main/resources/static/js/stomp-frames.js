// STOMP 1.2 프레임 인코딩/파싱과 재연결 대기 시간 계산. 화면·네트워크와 무관한 순수 함수라 node --test 로 테스트한다.

/** 헤더 값 이스케이프(STOMP 1.2: CONNECT/CONNECTED 프레임은 제외). */
function escapeHeader(value) {
  return String(value).replace(/\\/g, '\\\\').replace(/\r/g, '\\r').replace(/\n/g, '\\n').replace(/:/g, '\\c');
}

function unescapeHeader(value) {
  return value.replace(/\\(.)/g, (_, c) => ({ r: '\r', n: '\n', c: ':', '\\': '\\' }[c] ?? c));
}

export function encodeFrame(command, headers = {}, body = '') {
  const raw = command === 'CONNECT' || command === 'STOMP';
  let out = `${command}\n`;
  for (const [key, value] of Object.entries(headers)) {
    out += raw ? `${key}:${value}\n` : `${escapeHeader(key)}:${escapeHeader(value)}\n`;
  }
  return `${out}\n${body}\0`;
}

/** content-length는 UTF-8 바이트 수다. text에서 start부터 bytes바이트만큼 읽은 끝 위치를, 아직 모자라면 -1을 돌려준다. */
function endOfBytes(text, start, bytes) {
  let used = 0;
  let i = start;
  while (used < bytes) {
    if (i >= text.length) return -1;
    const code = text.codePointAt(i);
    used += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
    i += code > 0xffff ? 2 : 1;
  }
  return used === bytes ? i : -1;
}

/**
 * WebSocket 텍스트 메시지 조각을 받아 완성된 프레임 목록을 돌려준다.
 * 한 메시지에 여러 프레임이 오거나 한 프레임이 나뉘어 와도, 프레임 사이의 하트비트(빈 줄)도 처리한다.
 */
export class FrameParser {
  constructor() {
    this.buffer = '';
  }

  push(chunk) {
    this.buffer += chunk;
    const frames = [];
    for (;;) {
      this.buffer = this.buffer.replace(/^[\r\n]+/, ''); // 하트비트
      if (!this.buffer) break;
      const headerEnd = this.buffer.indexOf('\n\n');
      if (headerEnd < 0) break;
      const head = this.buffer.slice(0, headerEnd).replace(/\r/g, '').split('\n');
      const command = head[0];
      const headers = {};
      for (const line of head.slice(1)) {
        const colon = line.indexOf(':');
        if (colon < 0) continue;
        const key = unescapeHeader(line.slice(0, colon));
        if (!(key in headers)) headers[key] = unescapeHeader(line.slice(colon + 1)); // 같은 헤더는 첫 값이 유효
      }
      const bodyStart = headerEnd + 2;
      const length = headers['content-length'] === undefined ? NaN : Number(headers['content-length']);
      let bodyEnd;
      if (Number.isInteger(length) && length >= 0) {
        bodyEnd = endOfBytes(this.buffer, bodyStart, length);
        if (bodyEnd < 0 || this.buffer.length <= bodyEnd) break; // 본문과 NUL이 아직 다 안 왔다
      } else {
        bodyEnd = this.buffer.indexOf('\0', bodyStart);
        if (bodyEnd < 0) break;
      }
      frames.push({ command, headers, body: this.buffer.slice(bodyStart, bodyEnd) });
      this.buffer = this.buffer.slice(bodyEnd + 1);
    }
    return frames;
  }
}

/** 재연결 대기 시간(ms): 1초에서 시작해 두 배씩 늘어 최대 30초. jitter(0~1)로 ±20% 흩뜨려 동시에 몰리지 않게 한다. */
export function backoffDelay(attempt, jitter = 0.5) {
  const base = Math.min(30000, 1000 * 2 ** Math.max(0, attempt));
  return Math.round(base * (0.8 + 0.4 * jitter));
}
