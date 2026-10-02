import { mountSelect } from './select.js';
import { mountPlay } from './play.js';
import { mountResult } from './result.js';
import { mountLogin, mountSignup } from './auth-views.js';
import { clear, h } from './dom.js';
import * as auth from './auth.js';

const root = document.getElementById('app');
const navEl = document.getElementById('nav');
const toastEl = document.getElementById('toast');
let toastTimer;

const ctx = {
  /** 결과 화면으로 넘기는 값 (새로고침하면 사라진다 — 진도 저장은 2-3) */
  shared: { result: null },
  navigate(hash) {
    if (location.hash === hash) route();
    else location.hash = hash;
  },
  toast(message) {
    toastEl.textContent = message;
    toastEl.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (toastEl.hidden = true), 3500);
  },
};

function renderNav() {
  const user = auth.currentUser();
  clear(navEl);
  if (!user) {
    navEl.append(h('a', { href: '#/login' }, '로그인'), h('a', { href: '#/signup' }, '회원가입'));
    return;
  }
  navEl.append(h('span', { class: 'who' }, user.nickname, h('small', { class: 'muted' }, user.type === 'GUEST' ? ' (게스트)' : '')));
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

  const hash = location.hash || '#/';
  const play = hash.match(/^#\/play\/(\d+)$/);
  if (play) {
    mountPlay(root, Number(play[1]), ctx, handle);
  } else if (hash === '#/result' && ctx.shared.result) {
    mountResult(root, ctx, handle);
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
route();
