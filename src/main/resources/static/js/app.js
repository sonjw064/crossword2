import { mountSelect } from './select.js';
import { mountPlay } from './play.js';
import { mountResult } from './result.js';
import { clear } from './dom.js';

const root = document.getElementById('app');
const toastEl = document.getElementById('toast');
let toastTimer;

const ctx = {
  /** 결과 화면으로 넘기는 값 (새로고침하면 사라진다 — 진도 저장은 2단계) */
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

let current = null;

function route() {
  if (current) {
    current.cancelled = true;
    current.cleanup?.();
  }
  const handle = { cancelled: false, cleanup: null };
  current = handle;
  clear(root);
  window.scrollTo(0, 0);

  const hash = location.hash || '#/';
  const play = hash.match(/^#\/play\/(\d+)$/);
  if (play) {
    mountPlay(root, Number(play[1]), ctx, handle);
  } else if (hash === '#/result' && ctx.shared.result) {
    mountResult(root, ctx, handle);
  } else {
    if (hash !== '#/') history.replaceState(null, '', '#/');
    mountSelect(root, ctx, handle);
  }
}

window.addEventListener('hashchange', route);
route();
