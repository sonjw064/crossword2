import test from 'node:test';
import assert from 'node:assert/strict';
import { listen, singleSlot, createObjectUrlTracker } from '../../main/resources/static/js/lifecycle.js';

/** 등록된 리스너 수를 셀 수 있는 이벤트 대상. */
function countingTarget() {
  const target = new EventTarget();
  let listeners = 0;
  const add = target.addEventListener.bind(target);
  const remove = target.removeEventListener.bind(target);
  target.addEventListener = (...args) => { listeners++; add(...args); };
  target.removeEventListener = (...args) => { listeners--; remove(...args); };
  target.count = () => listeners;
  return target;
}

test('listen: 핸들러를 달고 제거하면 더 이상 호출되지 않는다', () => {
  const target = new EventTarget();
  let calls = 0;
  const off = listen(target, 'keydown', () => calls++);
  target.dispatchEvent(new Event('keydown'));
  off();
  target.dispatchEvent(new Event('keydown'));
  assert.equal(calls, 1);
});

test('listen: 제거 함수를 여러 번 불러도 안전하다', () => {
  const target = countingTarget();
  const off = listen(target, 'keydown', () => {});
  off();
  off();
  assert.equal(target.count(), 0);
});

test('singleSlot: 새로 설정하면 이전 것을 먼저 정리한다', () => {
  const log = [];
  const slot = singleSlot();
  slot.set(() => log.push('first'));
  slot.set(() => log.push('second'));
  assert.deepEqual(log, ['first']);
  slot.dispose();
  assert.deepEqual(log, ['first', 'second']);
  assert.equal(slot.active, false);
});

test('singleSlot: 비어 있을 때 dispose는 아무 일도 하지 않는다', () => {
  const slot = singleSlot();
  slot.dispose();
  slot.dispose();
  assert.equal(slot.active, false);
});

test('복습 시작/종료를 반복해도 리스너는 하나만 유지된다', () => {
  const doc = countingTarget();
  const slot = singleSlot();
  let calls = 0;
  const startReview = () => slot.set(listen(doc, 'keydown', () => calls++));
  const exitReview = () => slot.dispose();

  for (let i = 0; i < 50; i++) {
    startReview();
    assert.equal(doc.count(), 1, `시작 ${i}회째`);
    exitReview();
    assert.equal(doc.count(), 0, `종료 ${i}회째`);
  }
  startReview();
  startReview(); // 종료 없이 다시 시작해도 누적되지 않는다
  doc.dispatchEvent(new Event('keydown'));
  assert.equal(calls, 1, '키 입력 한 번에 핸들러는 한 번만 실행');
  slot.dispose(); // 화면을 떠날 때의 정리
  assert.equal(doc.count(), 0);
});

test('object URL 추적: 만든 주소를 모두 revoke한다', () => {
  const created = [];
  const revoked = [];
  const fakeUrl = { createObjectURL: (b) => { const u = `blob:${created.length}`; created.push(u); return u; }, revokeObjectURL: (u) => revoked.push(u) };
  const tracker = createObjectUrlTracker(fakeUrl);

  const a = tracker.create(new Blob(['a']));
  const b = tracker.create(new Blob(['b']));
  assert.equal(tracker.size, 2);
  tracker.revoke(a);
  assert.deepEqual(revoked, [a]);
  tracker.revokeAll();

  assert.deepEqual(revoked.sort(), [a, b].sort());
  assert.equal(tracker.size, 0);
});

test('object URL 추적: 모르는/이미 해제한 주소와 반복 revokeAll은 안전하다', () => {
  const revoked = [];
  const tracker = createObjectUrlTracker({ createObjectURL: () => 'blob:x', revokeObjectURL: (u) => revoked.push(u) });
  const url = tracker.create(new Blob(['a']));

  tracker.revoke('blob:unknown');
  tracker.revoke(url);
  tracker.revoke(url);
  tracker.revokeAll();
  tracker.revokeAll();

  assert.deepEqual(revoked, [url]);
});

test('첨부를 여러 번 열어도 화면을 떠나면 모두 해제된다', () => {
  let counter = 0;
  const live = new Set();
  const tracker = createObjectUrlTracker({
    createObjectURL: () => { const u = `blob:${counter++}`; live.add(u); return u; },
    revokeObjectURL: (u) => live.delete(u),
  });
  for (let i = 0; i < 10; i++) tracker.create(new Blob(['x']));
  assert.equal(live.size, 10);
  tracker.revokeAll();
  assert.equal(live.size, 0);
});
