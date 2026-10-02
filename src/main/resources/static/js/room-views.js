import { h, clear } from './dom.js';
import * as api from './api.js';
import * as auth from './auth.js';
import { StompClient } from './stomp-client.js';
import { createBattle } from './battle-views.js';
import {
  DIFFICULTY_LABEL, MODE_LABEL, applySnapshot, connectionText, inviteUrl, isHost, parseRoomCode, roomErrorText, startState,
} from './room-model.js';

const DEFAULT_SETTINGS = { difficulty: 'EASY', size: 7, timeLimitSec: 300, maxPlayers: 4, mode: 'RACE' };

/** 로그인(게스트 포함) 전이면 닉네임만 정하고 이어가게 한다. onReady는 로그인된 뒤 호출. */
function requireIdentity(root, title, onReady) {
  if (auth.currentUser()) return onReady();
  const nickname = h('input', { id: 'room-nick', type: 'text', maxlength: '20', autocomplete: 'off' });
  const error = h('p', { class: 'form-error', role: 'alert', hidden: true });
  const form = h('form', {
    onsubmit: async (e) => {
      e.preventDefault();
      error.hidden = true;
      try {
        await auth.startGuest(nickname.value.trim());
        clear(root);
        onReady();
      } catch (err) {
        error.textContent = err.message || '닉네임을 확인해 주세요.';
        error.hidden = false;
      }
    },
  },
  h('label', { for: 'room-nick' }, '닉네임'), nickname,
  h('button', { type: 'submit', class: 'primary' }, '게스트로 시작'),
  ' ', h('a', { class: 'button', href: '#/login' }, '로그인'), error);
  root.append(h('section', {}, h('h1', {}, title), h('p', { class: 'muted' }, '닉네임만 정하면 바로 참여할 수 있어요.'), form));
}

// ---------------- 방 만들기 / 코드로 입장 ----------------

export function mountRoomHome(root, ctx) {
  requireIdentity(root, '함께 풀기', () => renderHome(root, ctx));
}

function renderHome(root, ctx) {
  const error = h('p', { class: 'form-error', role: 'alert', hidden: true });
  const showError = (text) => {
    error.textContent = text;
    error.hidden = !text;
  };

  const code = h('input', { id: 'join-code', type: 'text', maxlength: '6', autocomplete: 'off', class: 'code-input' });
  const joinForm = h('form', {
    class: 'inline-form',
    onsubmit: (e) => {
      e.preventDefault();
      const parsed = parseRoomCode(code.value);
      if (!parsed) return showError('방 코드는 영문 대문자와 숫자 6자리예요.');
      showError('');
      ctx.navigate(`#/room/${parsed}`);
    },
  }, h('label', { for: 'join-code' }, '방 코드'), code, h('button', { type: 'submit' }, '입장'));

  const fields = settingsFields(DEFAULT_SETTINGS);
  const createButton = h('button', { type: 'submit', class: 'primary' }, '방 만들기');
  const createForm = h('form', {
    class: 'room-settings',
    onsubmit: async (e) => {
      e.preventDefault();
      showError('');
      createButton.disabled = true;
      try {
        const created = await api.createRoom(fields.read());
        ctx.navigate(`#/room/${created.code}`);
      } catch (err) {
        showError(roomErrorText(err));
        createButton.disabled = false;
      }
    },
  }, fields.element, createButton);

  root.append(h('section', {},
    h('h1', {}, '함께 풀기'),
    h('p', { class: 'muted' }, '친구와 같은 퍼즐을 동시에 풀고 속도를 겨뤄요.'),
    h('h2', {}, '방 만들기'), createForm,
    h('h2', {}, '코드로 입장'), joinForm, error));
}

/** 방 설정 입력칸 묶음. read()는 서버로 보낼 값을 돌려준다. */
function settingsFields(initial, { disabled = false } = {}) {
  const difficulty = h('select', { id: 'rs-difficulty', disabled },
    Object.entries(DIFFICULTY_LABEL).map(([value, label]) => h('option', { value, selected: value === initial.difficulty }, label)));
  const topic = h('input', { id: 'rs-topic', type: 'text', maxlength: '50', value: initial.topic ?? '', disabled, autocomplete: 'off' });
  const size = numberInput('rs-size', initial.size, 7, 15, disabled);
  const time = numberInput('rs-time', initial.timeLimitSec, 60, 3600, disabled);
  const players = numberInput('rs-players', initial.maxPlayers, 2, 8, disabled);
  const element = h('div', { class: 'settings-grid' },
    h('label', { for: 'rs-difficulty' }, '난이도'), difficulty,
    h('label', { for: 'rs-topic' }, '주제(선택)'), topic,
    h('label', { for: 'rs-size' }, '그리드 크기(7~15)'), size,
    h('label', { for: 'rs-time' }, '제한 시간(초)'), time,
    h('label', { for: 'rs-players' }, '최대 인원(2~8)'), players,
    h('span', {}, '모드'), h('span', {}, MODE_LABEL[initial.mode] ?? initial.mode));
  return {
    element,
    read: () => ({
      difficulty: difficulty.value,
      topic: topic.value.trim() || undefined,
      size: Number(size.value),
      timeLimitSec: Number(time.value),
      maxPlayers: Number(players.value),
      mode: initial.mode,
    }),
  };
}

function numberInput(id, value, min, max, disabled) {
  return h('input', { id, type: 'number', min, max, value, disabled, inputmode: 'numeric' });
}

// ---------------- 대기실 ----------------

export function mountRoom(root, rawCode, ctx, handle) {
  const code = parseRoomCode(rawCode);
  if (!code) {
    root.append(h('section', {}, h('h1', {}, '대기실'), h('p', { class: 'form-error' }, roomErrorText({ code: 'ROOM_NOT_FOUND' })),
      h('a', { class: 'button', href: '#/rooms' }, '돌아가기')));
    return;
  }
  requireIdentity(root, '대기실', () => renderRoom(root, code, ctx, handle));
}

function renderRoom(root, code, ctx, handle) {
  let room = null;
  let me = null; // 내 playerId
  let state = 'connecting';
  let left = false;
  let battle = null; // 경기 중/종료 후 화면(방송을 받을 때마다 갱신하되 DOM은 유지한다)
  let lastSolved = []; // 서버가 기억하는 내가 맞힌 단어들(새로고침/재접속 뒤 칸 복원용)

  const banner = h('p', { class: 'conn-banner', role: 'status', hidden: true });
  const errorEl = h('p', { class: 'form-error', role: 'alert', hidden: true });
  const body = h('div', {});
  root.append(h('section', { class: 'room' }, h('h1', {}, `대기실 ${code}`), banner, errorEl, body));

  const client = new StompClient({
    url: '/ws',
    getToken: () => auth.getAccessToken(),
    onAuthError: () => auth.refreshNow(),
    onState(next) {
      state = next;
      if (next === 'connected') {
        me = null; // 새 연결마다 다시 입장한다(재접속이면 같은 자리로 돌아온다)
        stopWatching();
        join();
      } else {
        stopWatching(); // 끊긴 사이에 방이 사라질 수 있어, 다시 입장이 확인된 뒤에 구독한다
      }
      draw();
    },
  });
  // 구독이 서버에 등록되기 전에 보낸 입장 요청의 응답은 놓칠 수 있다(STOMP에는 구독 확인이 없다).
  // 입장은 다시 보내도 같은 자리로 돌아올 뿐이라, 응답이 올 때까지 잠깐씩 간격을 두고 다시 보낸다.
  let joinTimer = null;
  function join(attempt = 0) {
    clearTimeout(joinTimer);
    if (me !== null || attempt >= 5 || !client.connected) return;
    client.send(`/app/rooms/${code}/join`);
    joinTimer = setTimeout(() => join(attempt + 1), 1000);
  }
  const command = (name, payload) => client.send(`/app/rooms/${code}/${name}`, payload);

  function showError(text) {
    errorEl.textContent = text;
    errorEl.hidden = !text;
  }

  client.subscribe('/user/queue/me', (ack) => {
    me = ack.playerId;
    room = applySnapshot(room, ack.room);
    lastSolved = ack.solved ?? [];
    battle?.restore(lastSolved);
    watchRoom();
    showError('');
    draw();
  });
  client.subscribe('/user/queue/submit', (ack) => battle?.onAck(ack));
  client.subscribe('/user/queue/errors', (err) => {
    if (battle?.onError(err.code)) return; // 제출 오류는 경기 화면이 안내한다
    showError(roomErrorText(err));
    if (!room && ['ROOM_NOT_FOUND', 'ROOM_FULL', 'ROOM_IN_PROGRESS', 'ALREADY_IN_ROOM'].includes(err.code)) {
      client.close(); // 들어갈 수 없는 방이면 재접속을 멈춘다
      draw();
    }
  });
  const onEvent = (event) => {
    if (event.type === 'CLOSED') {
      room = null;
      me = null;
      showError('방이 닫혔어요.');
      client.close();
    } else {
      const next = applySnapshot(room, event.room);
      // 내가 방에서 빠졌으면(게스트 이전 등) 더 이상 참가자가 아니다
      if (me !== null && !event.room.players.some((p) => p.playerId === me)) {
        room = null;
        me = null;
        showError('이 방에서 나왔어요.');
        client.close();
      } else {
        room = next;
      }
    }
    draw();
  };

  // 방 토픽은 입장이 확인된 뒤에 구독한다(없는 방이면 서버가 연결을 닫기 때문). 구독이 등록되는 사이에
  // 놓친 변경은 잠시 뒤 sync로 현재 상태를 받아 맞춘다.
  let unsubscribeTopic = null;
  let syncTimer = null;
  function watchRoom() {
    if (unsubscribeTopic) return;
    unsubscribeTopic = client.subscribe(`/topic/rooms/${code}`, onEvent);
    syncTimer = setTimeout(() => command('sync'), 500);
  }
  function stopWatching() {
    clearTimeout(syncTimer);
    unsubscribeTopic?.();
    unsubscribeTopic = null;
  }

  function draw() {
    const text = connectionText(state);
    banner.textContent = text;
    banner.hidden = !text || left;
    clear(body);
    if (!room) {
      stopBattle();
      body.append(h('p', {}, state === 'connected' ? '입장하는 중…' : ''), h('a', { class: 'button', href: '#/rooms' }, '나가기'));
      return;
    }
    if (room.status === 'PLAYING' || room.status === 'ENDED') {
      if (!battle) {
        battle = createBattle({ code, ctx, command, isHost: () => isHost(room, me) });
        battle.restore(lastSolved);
      }
      battle.update(room, me);
      body.append(battle.element, leaveButton());
      return;
    }
    stopBattle(); // 다시 하기로 대기실에 돌아오면 경기 화면을 버린다
    body.append(playersView(), settingsView(), actionsView());
  }

  function stopBattle() {
    battle?.destroy();
    battle = null;
  }

  function leaveButton() {
    return h('p', {}, h('button', {
      type: 'button',
      class: 'link',
      onclick: () => {
        if (room?.status === 'PLAYING' && !confirm('경기 중에 나가면 이어서 할 수 없어요. 나갈까요?')) return;
        left = true;
        command('leave');
        client.close();
        ctx.navigate('#/rooms');
      },
    }, '방 나가기'));
  }

  function playersView() {
    return h('ul', { class: 'player-list' }, room.players.map((p) => h('li', {
      class: `player${p.connected ? '' : ' offline'}`,
    },
    h('span', { class: 'player-name' }, p.nickname, p.playerId === me ? h('small', { class: 'muted' }, ' (나)') : null),
    p.host ? h('span', { class: 'tag host' }, '방장') : null,
    p.host || p.ready ? h('span', { class: 'tag ready' }, '준비 완료') : h('span', { class: 'tag' }, '대기 중'),
    p.connected ? null : h('span', { class: 'tag offline' }, '연결 끊김'))));
  }

  function settingsView() {
    const host = isHost(room, me);
    const editable = host && room.status === 'WAITING';
    const fields = settingsFields(room.settings, { disabled: !editable });
    if (!editable) {
      return h('div', { class: 'room-settings' }, h('h2', {}, '설정'), fields.element);
    }
    return h('form', {
      class: 'room-settings',
      onsubmit: (e) => {
        e.preventDefault();
        command('settings', fields.read());
      },
    }, h('h2', {}, '설정'), fields.element, h('button', { type: 'submit' }, '설정 저장'));
  }

  function actionsView() {
    const host = isHost(room, me);
    const start = startState(room, me);
    const link = inviteUrl(location.origin, code);
    const myself = room.players.find((p) => p.playerId === me);
    const parts = [
      h('div', { class: 'invite' },
        h('input', { type: 'text', readonly: true, value: link, 'aria-label': '초대 링크', onfocus: (e) => e.target.select() }),
        h('button', {
          type: 'button',
          onclick: async () => {
            try {
              await navigator.clipboard.writeText(link);
              ctx.toast('초대 링크를 복사했어요.');
            } catch {
              ctx.toast('링크를 직접 선택해서 복사해 주세요.');
            }
          },
        }, '링크 복사')),
    ];
    if (host) {
      parts.push(h('button', { type: 'button', class: 'primary', disabled: !start.enabled, onclick: () => command('start') }, '시작'));
      if (start.reason) parts.push(h('small', { class: 'muted' }, ` ${start.reason}`));
    } else if (myself) {
      parts.push(h('button', {
        type: 'button', class: myself.ready ? '' : 'primary', onclick: () => command('ready', { ready: !myself.ready }),
      }, myself.ready ? '준비 취소' : '준비'));
    }
    parts.push(h('button', {
      type: 'button',
      class: 'link',
      onclick: () => {
        left = true;
        command('leave');
        client.close();
        ctx.navigate('#/rooms');
      },
    }, '방 나가기'));
    return h('div', { class: 'room-actions' }, parts);
  }

  handle.cleanup = () => {
    clearTimeout(joinTimer);
    clearTimeout(syncTimer);
    stopBattle();
    client.close();
  }; // 화면을 떠나면 연결을 닫는다(서버는 유예 시간 동안 자리를 지켜 준다)
  draw();
  client.connect();
}
