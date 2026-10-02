import test from 'node:test';
import assert from 'node:assert/strict';

// auth.js는 모듈 수준 상태를 가지므로 테스트마다 새 인스턴스를 불러온다. 저장소와 fetch는 가짜로 대체한다.
let counter = 0;
const authUrl = new URL('../../main/resources/static/js/auth.js', import.meta.url).href;

function fakeStorage(initial = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (k) => (data.has(k) ? data.get(k) : null),
    setItem: (k, v) => data.set(k, String(v)),
    removeItem: (k) => data.delete(k),
    raw: (k) => data.get(k) ?? null,
  };
}

function tokenResponse(name, refresh, expiresIn = 10, ownerType = 'MEMBER') {
  // expiresIn이 60초 미만이면 getAccessToken이 곧바로 refresh를 시도한다
  return { accessToken: `access-${name}`, expiresIn, refreshToken: refresh, ownerType, ownerId: ownerType === 'GUEST' ? 'guest-uuid-1' : '1', nickname: name, role: 'USER' };
}

const json = (status, body) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) });

function deferred() {
  let resolve;
  const promise = new Promise((r) => (resolve = r));
  return { promise, resolve };
}

/** routes: { '/api/auth/login': (body) => response, ... }. 호출 기록은 calls에 쌓인다. */
async function setup(routes, storage = fakeStorage()) {
  const calls = [];
  globalThis.localStorage = storage;
  globalThis.fetch = async (url, init) => {
    const body = init?.body ? JSON.parse(init.body) : undefined;
    calls.push({ url, body, headers: init?.headers ?? {} });
    const handler = routes[url];
    if (!handler) throw new Error(`unexpected request ${url}`);
    return handler(body);
  };
  const auth = await import(`${authUrl}?t=${counter++}`);
  return { auth, calls, storage };
}

const callsTo = (calls, url) => calls.filter((c) => c.url === url);

test('로그인하면 사용자와 토큰이 저장된다', async () => {
  const { auth, storage } = await setup({ '/api/auth/login': () => json(200, tokenResponse('kim', 'r1', 1800)) });
  await auth.login('a@b.com', 'secret123');
  assert.equal(auth.currentUser().nickname, 'kim');
  assert.equal(JSON.parse(storage.raw('crossword.auth')).refreshToken, 'r1');
  assert.equal(await auth.getAccessToken(), 'access-kim'); // 아직 만료 전: refresh 안 함
});

test('동시에 여러 번 토큰을 요청해도 refresh는 한 번만 보낸다', async () => {
  const gate = deferred();
  const { auth, calls } = await setup({
    '/api/auth/login': () => json(200, tokenResponse('kim', 'r1')),
    '/api/auth/refresh': async () => { await gate.promise; return json(200, tokenResponse('kim', 'r2', 1800)); },
  });
  await auth.login('a@b.com', 'secret123');
  const all = Promise.all([auth.getAccessToken(), auth.getAccessToken(), auth.getAccessToken()]);
  gate.resolve();
  assert.deepEqual(await all, ['access-kim', 'access-kim', 'access-kim']);
  assert.equal(callsTo(calls, '/api/auth/refresh').length, 1);
});

test('refresh 도중 로그아웃하면 늦게 온 응답은 버리고 새 토큰을 폐기한다', async () => {
  const gate = deferred();
  const { auth, calls, storage } = await setup({
    '/api/auth/login': () => json(200, tokenResponse('kim', 'r1')),
    '/api/auth/refresh': async () => { await gate.promise; return json(200, tokenResponse('kim', 'r2-stale', 1800)); },
    '/api/auth/logout': () => json(204, null),
  });
  await auth.login('a@b.com', 'secret123');
  const pending = auth.getAccessToken(); // refresh 시작
  await auth.logout();
  gate.resolve(); // 이전 refresh 응답이 이제야 도착
  await pending;

  assert.equal(auth.currentUser(), null);
  assert.equal(storage.raw('crossword.auth'), null);
  const revoked = callsTo(calls, '/api/auth/logout').map((c) => c.body.refreshToken);
  assert.ok(revoked.includes('r1'), '로그아웃 때 원래 토큰 폐기');
  assert.ok(revoked.includes('r2-stale'), '늦게 발급된 토큰도 폐기');
});

test('refresh 도중 다른 계정으로 로그인하면 이전 계정의 응답이 덮어쓰지 못한다', async () => {
  const gate = deferred();
  let loginCount = 0;
  const { auth, storage } = await setup({
    '/api/auth/login': () => json(200, loginCount++ === 0 ? tokenResponse('kim', 'k1') : tokenResponse('lee', 'l1', 1800)),
    '/api/auth/refresh': async () => { await gate.promise; return json(200, tokenResponse('kim', 'k2-stale', 1800)); },
    '/api/auth/logout': () => json(204, null),
  });
  await auth.login('kim@b.com', 'secret123');
  const pending = auth.getAccessToken();
  await auth.login('lee@b.com', 'secret123');
  gate.resolve();
  await pending;

  assert.equal(auth.currentUser().nickname, 'lee');
  assert.equal(JSON.parse(storage.raw('crossword.auth')).refreshToken, 'l1');
});

test('refresh가 401이어도 다른 탭이 이미 갱신했다면 로그아웃하지 않고 이어받는다', async () => {
  const { auth, storage } = await setup({
    '/api/auth/login': () => json(200, tokenResponse('kim', 'r1')),
    '/api/auth/refresh': () => {
      // 다른 탭이 먼저 교체를 끝내고 저장소를 갱신해 둔 상황
      storage.setItem('crossword.auth', JSON.stringify({
        accessToken: 'access-from-other-tab', refreshToken: 'r2-other-tab', expiresAt: Date.now() + 1800000,
        user: { type: 'MEMBER', id: '1', nickname: 'kim', role: 'USER' },
      }));
      return json(401, { code: 'INVALID_REFRESH_TOKEN' });
    },
  });
  await auth.login('a@b.com', 'secret123');

  assert.equal(await auth.getAccessToken(), 'access-from-other-tab');
  assert.equal(auth.currentUser().nickname, 'kim');
});

test('refresh가 401이고 저장소도 그대로면 로그아웃 상태가 된다', async () => {
  const { auth, storage } = await setup({
    '/api/auth/login': () => json(200, tokenResponse('kim', 'r1')),
    '/api/auth/refresh': () => json(401, { code: 'INVALID_REFRESH_TOKEN' }),
  });
  await auth.login('a@b.com', 'secret123');

  await auth.getAccessToken();

  assert.equal(auth.currentUser(), null);
  assert.equal(storage.raw('crossword.auth'), null);
});

test('네트워크 오류로 refresh에 실패하면 로그인 상태를 유지한다', async () => {
  const { auth } = await setup({
    '/api/auth/login': () => json(200, tokenResponse('kim', 'r1')),
    '/api/auth/refresh': () => { throw new TypeError('fetch failed'); },
  });
  await auth.login('a@b.com', 'secret123');

  assert.equal(await auth.getAccessToken(), 'access-kim');
  assert.equal(auth.currentUser().nickname, 'kim');
});

test('로그아웃은 서버 폐기에 실패해도 이 기기의 상태를 지운다', async () => {
  const { auth, storage } = await setup({
    '/api/auth/login': () => json(200, tokenResponse('kim', 'r1', 1800)),
    '/api/auth/logout': () => { throw new TypeError('offline'); },
  });
  await auth.login('a@b.com', 'secret123');

  await auth.logout();

  assert.equal(auth.currentUser(), null);
  assert.equal(storage.raw('crossword.auth'), null);
});

test('저장된 로그인 상태를 불러오고, 저장소를 못 쓰면 메모리로만 동작한다', async () => {
  const saved = JSON.stringify({ accessToken: 'a', refreshToken: 'r', expiresAt: Date.now() + 1800000, user: { type: 'GUEST', id: 'g', nickname: '손님', role: null } });
  const withStorage = await setup({}, fakeStorage({ 'crossword.auth': saved }));
  assert.equal(withStorage.auth.currentUser().nickname, '손님');

  const broken = await setup({ '/api/auth/login': () => json(200, tokenResponse('kim', 'r1', 1800)) }, {
    getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); }, removeItem() { throw new Error('blocked'); },
  });
  await broken.auth.login('a@b.com', 'secret123');
  assert.equal(broken.auth.currentUser().nickname, 'kim');
});

// ---- 게스트 기록 이전 (가입/로그인 때 게스트 증명을 함께 보낸다) ----

test('게스트로 가입하면 게스트 ID와 게스트 토큰을 함께 보내고, 이어받았는지 알려준다', async () => {
  const { auth, calls } = await setup({
    '/api/auth/guest': () => json(200, tokenResponse('손님', 'g1', 1800, 'GUEST')),
    '/api/auth/signup': () => json(201, { ...tokenResponse('kim', 'm1', 1800), guestMigrated: true }),
  });
  await auth.startGuest('손님');

  const result = await auth.signup('a@b.com', 'secret123', 'kim');

  const [call] = callsTo(calls, '/api/auth/signup');
  assert.equal(call.body.guestId, 'guest-uuid-1');
  assert.equal(call.headers.Authorization, 'Bearer access-손님');
  assert.deepEqual(result, { guestMigrated: true });
  assert.equal(auth.currentUser().type, 'MEMBER');
});

test('게스트가 아니면 증명을 보내지 않는다', async () => {
  const { auth, calls } = await setup({
    '/api/auth/login': () => json(200, tokenResponse('kim', 'm1', 1800)),
    '/api/auth/signup': () => json(201, tokenResponse('lee', 'm2', 1800)),
  });

  await auth.login('a@b.com', 'secret123');
  await auth.signup('c@d.com', 'secret123', 'lee');

  for (const url of ['/api/auth/login', '/api/auth/signup']) {
    const [call] = callsTo(calls, url);
    assert.equal(call.body.guestId, undefined);
    assert.equal(call.headers.Authorization, undefined);
  }
});

test('게스트 토큰이 만료돼 있으면 먼저 갱신한 토큰으로 증명한다', async () => {
  const { auth, calls } = await setup({
    '/api/auth/guest': () => json(200, tokenResponse('손님', 'g1', 10, 'GUEST')), // 곧 만료
    '/api/auth/refresh': () => json(200, tokenResponse('손님', 'g2', 1800, 'GUEST')),
    '/api/auth/signup': () => json(201, { ...tokenResponse('kim', 'm1', 1800), guestMigrated: true }),
  });
  await auth.startGuest('손님');

  await auth.signup('a@b.com', 'secret123', 'kim');

  assert.equal(callsTo(calls, '/api/auth/refresh').length, 1);
  assert.equal(callsTo(calls, '/api/auth/signup')[0].headers.Authorization, 'Bearer access-손님');
});

test('서버가 게스트 정보를 거부하면 게스트 상태를 지우고 다시 시도하게 안내한다', async () => {
  for (const [status, code] of [[401, 'UNAUTHORIZED'], [403, 'GUEST_PROOF_INVALID'], [409, 'GUEST_ALREADY_MIGRATED']]) {
    const { auth, storage } = await setup({
      '/api/auth/guest': () => json(200, tokenResponse('손님', 'g1', 1800, 'GUEST')),
      '/api/auth/signup': () => json(status, { code }),
    });
    await auth.startGuest('손님');

    await assert.rejects(auth.signup('a@b.com', 'secret123', 'kim'), (e) => e.code === 'GUEST_REJECTED');

    assert.equal(auth.currentUser(), null, `${code}: 게스트 상태 정리`);
    assert.equal(storage.raw('crossword.auth'), null);
  }
});

test('비밀번호가 틀린 것(401)은 게스트 거부로 취급하지 않고 게스트 상태를 유지한다', async () => {
  const { auth } = await setup({
    '/api/auth/guest': () => json(200, tokenResponse('손님', 'g1', 1800, 'GUEST')),
    '/api/auth/login': () => json(401, { code: 'INVALID_CREDENTIALS' }),
  });
  await auth.startGuest('손님');

  await assert.rejects(auth.login('a@b.com', 'wrongpass1'), (e) => e.code === 'INVALID_CREDENTIALS');

  assert.equal(auth.currentUser().type, 'GUEST');
});

test('이메일 중복 같은 일반 오류는 게스트 상태를 건드리지 않는다', async () => {
  const { auth } = await setup({
    '/api/auth/guest': () => json(200, tokenResponse('손님', 'g1', 1800, 'GUEST')),
    '/api/auth/signup': () => json(409, { code: 'EMAIL_TAKEN' }),
  });
  await auth.startGuest('손님');

  await assert.rejects(auth.signup('a@b.com', 'secret123', 'kim'), (e) => e.code === 'EMAIL_TAKEN');

  assert.equal(auth.currentUser().type, 'GUEST');
});
