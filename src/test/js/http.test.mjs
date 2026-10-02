import test from 'node:test';
import assert from 'node:assert/strict';
import { rawRequest, ApiError } from '../../main/resources/static/js/http.js';

const json = (status, body) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) });

function mockFetch(handler) {
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init });
    return handler(url, init);
  };
  return calls;
}

test('JSON 본문은 문자열로 보내고 Content-Type을 지정한다', async () => {
  const calls = mockFetch(() => json(200, { ok: true }));
  await rawRequest('POST', '/api/x', { body: { a: 1 } });
  assert.equal(calls[0].init.headers['Content-Type'], 'application/json');
  assert.equal(calls[0].init.body, '{"a":1}');
});

test('FormData는 그대로 보내고 Content-Type을 직접 지정하지 않는다(브라우저가 boundary를 붙임)', async () => {
  const calls = mockFetch(() => json(201, { id: 1 }));
  const form = new FormData();
  form.append('data', new Blob(['{"type":"BUG"}'], { type: 'application/json' }));
  form.append('screenshot', new Blob(['png-bytes'], { type: 'image/png' }), 'a.png');

  const result = await rawRequest('POST', '/api/feedback', { body: form, token: 't1' });

  assert.equal(result.id, 1);
  assert.equal(calls[0].init.body, form);
  assert.equal(calls[0].init.headers['Content-Type'], undefined);
  assert.equal(calls[0].init.headers.Authorization, 'Bearer t1');
});

test('본문이 없으면 본문과 Content-Type을 보내지 않는다', async () => {
  const calls = mockFetch(() => json(200, {}));
  await rawRequest('GET', '/api/x');
  assert.equal(calls[0].init.body, undefined);
  assert.equal(calls[0].init.headers['Content-Type'], undefined);
});

test('세션 헤더와 토큰을 붙인다', async () => {
  const calls = mockFetch(() => json(200, {}));
  await rawRequest('GET', '/api/words/1', { session: 'sess-1', token: 'tok' });
  assert.equal(calls[0].init.headers['X-Play-Session'], 'sess-1');
  assert.equal(calls[0].init.headers.Authorization, 'Bearer tok');
});

test('서버 오류 코드를 한국어 안내로 바꾼다', async () => {
  mockFetch(() => json(413, { code: 'ATTACHMENT_TOO_LARGE' }));
  await assert.rejects(rawRequest('POST', '/api/feedback', { body: new FormData() }), (e) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.status, 413);
    assert.equal(e.code, 'ATTACHMENT_TOO_LARGE');
    assert.match(e.message, /2MB/);
    return true;
  });
});

test('알 수 없는 오류는 일반 문구, JSON이 아닌 응답도 안전하게 처리한다', async () => {
  mockFetch(() => ({ ok: false, status: 502, text: async () => '<html>bad gateway</html>' }));
  await assert.rejects(rawRequest('GET', '/api/x'), (e) => e.code === 'ERROR' && e.status === 502);
});

test('네트워크 오류는 NETWORK 코드', async () => {
  mockFetch(() => { throw new TypeError('fetch failed'); });
  await assert.rejects(rawRequest('GET', '/api/x'), (e) => e.code === 'NETWORK' && e.status === 0);
});
