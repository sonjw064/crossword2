import { h } from './dom.js';
import * as auth from './auth.js';

const NICKNAME = /^[\p{L}\p{N}_-][\p{L}\p{N}_ -]{0,18}[\p{L}\p{N}_-]$/u;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export const nicknameError = (value) =>
  NICKNAME.test(value) ? '' : '닉네임은 2~20자(글자, 숫자, 밑줄, 하이픈, 공백)로 입력해 주세요.';

export const passwordError = (value) =>
  value.length >= 8 && value.length <= 72 && /\p{L}/u.test(value) && /\d/.test(value)
    ? ''
    : '비밀번호는 8자 이상이고 글자와 숫자를 모두 포함해야 해요.';

function field(label, input) {
  return h('label', { class: 'field' }, h('span', { class: 'label' }, label), input);
}

/** 폼 공통: 오류 문구, 제출 중 버튼 비활성화. */
function form(buttonLabel, validate, submit) {
  const errorEl = h('p', { class: 'form-error', role: 'alert', hidden: true });
  const button = h('button', { type: 'submit', class: 'primary' }, buttonLabel);
  const el = h('form', {
    class: 'auth-form',
    novalidate: true,
    onsubmit: async (e) => {
      e.preventDefault();
      const problem = validate();
      if (problem) return show(problem);
      button.disabled = true;
      show('');
      try {
        await submit();
      } catch (err) {
        show(err.message);
        button.disabled = false;
      }
    },
  });
  function show(message) {
    errorEl.textContent = message;
    errorEl.hidden = !message;
  }
  return { el, errorEl, button };
}

export function mountLogin(root, ctx) {
  const email = h('input', { type: 'email', autocomplete: 'username', required: true });
  const password = h('input', { type: 'password', autocomplete: 'current-password', required: true });
  const f = form('로그인',
    () => (!EMAIL.test(email.value.trim()) ? '이메일을 확인해 주세요.' : !password.value ? '비밀번호를 입력해 주세요.' : ''),
    async () => {
      await auth.login(email.value.trim(), password.value);
      ctx.navigate('#/');
    });
  f.el.append(field('이메일', email), field('비밀번호', password), f.errorEl, f.button);
  root.append(h('section', { class: 'auth' },
    h('h1', {}, '로그인'), f.el,
    h('p', { class: 'muted' }, '계정이 없나요? ', h('a', { href: '#/signup' }, '회원가입'))));
}

export function mountSignup(root, ctx) {
  const guest = auth.currentUser()?.type === 'GUEST' ? auth.currentUser() : null;
  const email = h('input', { type: 'email', autocomplete: 'username', required: true });
  const nickname = h('input', { type: 'text', autocomplete: 'nickname', maxlength: '20', required: true, value: guest?.nickname ?? '' });
  const password = h('input', { type: 'password', autocomplete: 'new-password', required: true });
  const f = form('가입하기',
    () => (!EMAIL.test(email.value.trim()) ? '이메일을 확인해 주세요.'
      : nicknameError(nickname.value.trim()) || passwordError(password.value)),
    async () => {
      await auth.signup(email.value.trim(), password.value, nickname.value.trim());
      ctx.navigate('#/');
    });
  f.el.append(
    field('이메일', email), field('닉네임', nickname), field('비밀번호', password),
    h('p', { class: 'muted' }, '비밀번호는 8자 이상, 글자와 숫자를 모두 포함해 주세요.'),
    f.errorEl, f.button);
  root.append(h('section', { class: 'auth' },
    h('h1', {}, '회원가입'), f.el,
    h('p', { class: 'muted' }, '이미 계정이 있나요? ', h('a', { href: '#/login' }, '로그인'))));
}
