import test from 'node:test';
import assert from 'node:assert/strict';
import {
  applySnapshot, connectionText, inviteUrl, parseRoomCode, roomCodeFromPath, roomErrorText, startState,
} from '../../main/resources/static/js/room-model.js';

const player = (id, extra = {}) => ({ playerId: id, nickname: `p${id}`, host: false, ready: false, connected: true, ...extra });
const room = (players, extra = {}) => ({ code: 'ABC234', status: 'WAITING', version: 1, players, ...extra });

test('parseRoomCode: 소문자와 공백을 정리하고 형식이 틀리면 null', () => {
  assert.equal(parseRoomCode(' abc234 '), 'ABC234');
  assert.equal(parseRoomCode('ABC23'), null);
  assert.equal(parseRoomCode('ABC2345'), null);
  assert.equal(parseRoomCode('ABC0O1'), null); // 헷갈리는 글자는 코드에 없다
  assert.equal(parseRoomCode('ABC-34'), null);
  assert.equal(parseRoomCode(null), null);
});

test('roomCodeFromPath: 초대 링크 경로에서만 코드를 꺼낸다', () => {
  assert.equal(roomCodeFromPath('/room/abc234'), 'ABC234');
  assert.equal(roomCodeFromPath('/room/ABC234/'), 'ABC234');
  assert.equal(roomCodeFromPath('/room/nope'), null);
  assert.equal(roomCodeFromPath('/rooms/ABC234'), null);
  assert.equal(roomCodeFromPath('/'), null);
  assert.equal(roomCodeFromPath('/room/%E0%A4%A'), null); // 깨진 인코딩도 예외 없이 null
});

test('inviteUrl', () => {
  assert.equal(inviteUrl('http://localhost:8080', 'ABC234'), 'http://localhost:8080/room/ABC234');
});

test('applySnapshot: 더 오래된 방송은 버리고 같거나 새로운 것만 받는다', () => {
  const v3 = room([], { version: 3 });
  assert.equal(applySnapshot(v3, room([], { version: 2 })), v3);
  const v4 = room([], { version: 4 });
  assert.equal(applySnapshot(v3, v4), v4);
  const again = room([], { version: 3 });
  assert.equal(applySnapshot(v3, again), again);
  assert.equal(applySnapshot(null, v3), v3);
  assert.equal(applySnapshot(v3, { no: 'version' }), v3);
});

test('startState: 방장이 아니면 시작할 수 없다', () => {
  const r = room([player(1, { host: true }), player(2, { ready: true })]);
  assert.equal(startState(r, 2).enabled, false);
});

test('startState: 혼자면 시작할 수 없다', () => {
  assert.equal(startState(room([player(1, { host: true })]), 1).enabled, false);
});

test('startState: 준비하지 않았거나 연결이 끊긴 참가자를 기다린다', () => {
  const waiting = room([player(1, { host: true }), player(2), player(3, { ready: true, connected: false })]);
  const s = startState(waiting, 1);
  assert.equal(s.enabled, false);
  assert.match(s.reason, /p2/);
  assert.match(s.reason, /p3/);
});

test('startState: 모두 준비하면 방장이 시작할 수 있다', () => {
  const ok = room([player(1, { host: true }), player(2, { ready: true })]);
  assert.deepEqual(startState(ok, 1), { enabled: true, reason: '' });
});

test('startState: 대기 중이 아니면 비활성', () => {
  assert.equal(startState(room([], { status: 'PLAYING' }), 1).enabled, false);
});

test('roomErrorText: 알려진 코드는 문구로, 모르는 코드는 일반 문구로', () => {
  assert.match(roomErrorText({ code: 'ROOM_FULL' }), /가득/);
  assert.match(roomErrorText({ code: '???' }), /문제가 생겼어요/);
  assert.match(roomErrorText({ code: 'ALREADY_IN_ROOM', message: 'you are already in room ABC234' }), /ABC234/);
});

test('connectionText', () => {
  assert.equal(connectionText('connected'), '');
  assert.match(connectionText('connecting'), /연결하는 중/);
  assert.match(connectionText('disconnected'), /끊겼어요/);
});
