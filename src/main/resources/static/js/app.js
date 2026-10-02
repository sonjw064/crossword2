import { mountSelect } from './select.js';
import { mountPlay } from './play.js';
import { mountResult } from './result.js';
import { mountLogin, mountSignup } from './auth-views.js';
import { mountProgress, mountWrongAnswers } from './progress-views.js';
import { mountFeedbackForm, mountMyFeedback } from './feedback-views.js';
import { unreadBadge } from './feedback-model.js';
import { mountRoomHome, mountRoom } from './room-views.js';
import { roomCodeFromPath } from './room-model.js';
import * as api from './api.js';
import { clear, h } from './dom.js';
import * as auth from './auth.js';

const root = document.getElementById('app');
const navEl = document.getElementById('nav');
const toastEl = document.getElementById('toast');
let toastTimer;

const ctx = {
  /** 결과 화면으로 넘기는 값 (새로고침하면 사라진다) */
  shared: { result: null },
  navigate(hash) {
    if (location.hash === hash) route();
    else location.hash = hash;
  },
  /** 헤더의 "내 문의" 읽지 않음 뱃지를 다시 불러온다(회원만). */
  refreshBadge,
  toast(message) {
    toastEl.textContent = message;
    toastEl.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (toastEl.hidden = true), 3500);
  },
};

const badgeEl = h('span', { class: 'badge-count', hidden: true, 'aria-label': '읽지 않은 답변' });

async function refreshBadge() {
  const user = auth.currentUser();
  let text = '';
  if (user?.type === 'MEMBER') {
    try {
      text = unreadBadge((await api.getUnreadCount()).count);
    } catch {
      // 뱃지는 부가 정보라 실패해도 조용히 숨긴다
    }
  }
  badgeEl.textContent = text;
  badgeEl.hidden = !text;
}

function renderNav() {
  const user = auth.currentUser();
  clear(navEl);
  if (!user) {
    navEl.append(h('a', { href: '#/login' }, '로그인'), h('a', { href: '#/signup' }, '회원가입'));
    return;
  }
  navEl.append(
    h('a', { href: '#/rooms' }, '함께 풀기'), h('a', { href: '#/me' }, '내 기록'), h('a', { href: '#/wrong-answers' }, '오답노트'),
    h('a', { href: '#/feedback' }, '문의하기'),
    h('span', { class: 'who' }, user.nickname, h('small', { class: 'muted' }, user.type === 'GUEST' ? ' (게스트)' : '')));
  if (user.type === 'MEMBER') {
    navEl.append(h('a', { href: '#/my-feedback' }, '내 문의', badgeEl)); // 회원만: 답변은 회원만 받을 수 있다
  }
  if (user.type === 'GUEST') {
    navEl.append(h('a', { href: '#/login' }, '로그인'), h('a', { href: '#/signup' }, '회원가입'));
  }
  navEl.append(h('button', {
    type: 'button',
    class: 'link',
    onclick: async () => {
      await auth.logout();
      ctx.navigate('#/');
    },
  }, '로그아웃'));
}

let current = null;

function route() {
  if (current) {
    current.cancelled = true;
    current.cleanup?.();
  }
  const handle = { cancelled: false, cleanup: null };
  current = handle;
  clear(root);
  renderNav();
  window.scrollTo(0, 0);

  const [hash, queryString = ''] = (location.hash || '#/').split('?');
  const query = new URLSearchParams(queryString);
  refreshBadge();
  const room = hash.match(/^#\/room\/([^/]+)$/);
  const play = hash.match(/^#\/play\/(\d+)$/);
  if (play) {
    mountPlay(root, Number(play[1]), ctx, handle);
  } else if (hash === '#/result' && ctx.shared.result) {
    mountResult(root, ctx, handle);
  } else if (hash === '#/me') {
    mountProgress(root, ctx, handle);
  } else if (hash === '#/wrong-answers') {
    mountWrongAnswers(root, ctx, handle);
  } else if (hash === '#/feedback') {
    mountFeedbackForm(root, ctx, handle, query);
  } else if (hash === '#/my-feedback') {
    mountMyFeedback(root, ctx, handle);
  } else if (hash === '#/rooms') {
    mountRoomHome(root, ctx);
  } else if (room) {
    mountRoom(root, room[1], ctx, handle);
  } else if (hash === '#/login') {
    mountLogin(root, ctx);
  } else if (hash === '#/signup') {
    mountSignup(root, ctx);
  } else {
    if (hash !== '#/') history.replaceState(null, '', '#/');
    mountSelect(root, ctx, handle);
  }
}

// 로그인 상태가 바뀌면(로그인/로그아웃/토큰 만료) 헤더를 갱신한다
auth.onChange(renderNav);
window.addEventListener('hashchange', route);

// 초대 링크(/room/ABC234)로 들어오면 주소를 해시 경로로 바꿔 같은 화면을 연다
const invited = roomCodeFromPath(location.pathname);
if (invited) history.replaceState(null, '', `/#/room/${invited}`);
else if (location.pathname.startsWith('/room/')) history.replaceState(null, '', '/#/rooms');
route();
