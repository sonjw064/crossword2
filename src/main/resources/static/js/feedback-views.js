import { h, clear } from './dom.js';
import * as api from './api.js';
import * as auth from './auth.js';
import { formatDateTime } from './labels.js';
import { createObjectUrlTracker } from './lifecycle.js';
import {
  LIMITS, REASONS, STATUS_LABEL, TYPES, labelOf, screenInfo, validateFeedback,
} from './feedback-model.js';

const GUEST_NOTICE = '회원가입하면 이 문의의 답변을 받을 수 있어요.';

/** 게스트에게만 보이는 안내(답변은 회원만 받을 수 있다). */
function guestNotice() {
  return h('p', { class: 'guest-banner' }, GUEST_NOTICE, ' ', h('a', { href: '#/signup' }, '회원가입'));
}

function needIdentity(root) {
  root.append(h('section', {},
    h('h1', {}, '문의하기'),
    h('p', { class: 'muted' }, '닉네임만 정하면(게스트) 바로 문의를 남길 수 있어요.'),
    h('p', {}, h('a', { class: 'button primary', href: '#/' }, '시작하기'), ' ',
      h('a', { class: 'button', href: '#/login' }, '로그인'))));
}

// ---------------- 문의 작성 ----------------

export function mountFeedbackForm(root, ctx, handle, query = new URLSearchParams()) {
  const user = auth.currentUser();
  if (!user) return needIdentity(root);

  const puzzleParam = Number(query.get('puzzle'));
  const puzzleId = Number.isInteger(puzzleParam) && puzzleParam > 0 ? puzzleParam : undefined;

  const type = h('select', { id: 'fb-type' },
    TYPES.map((t) => h('option', { value: t.value }, t.label)));
  const title = h('input', { id: 'fb-title', type: 'text', maxlength: String(LIMITS.title), autocomplete: 'off' });
  const content = h('textarea', { id: 'fb-content', rows: '6', maxlength: String(LIMITS.content) });
  const counter = h('small', { class: 'muted' }, `0 / ${LIMITS.content}`);
  content.addEventListener('input', () => (counter.textContent = `${content.value.length} / ${LIMITS.content}`));
  const file = h('input', { id: 'fb-file', type: 'file', accept: LIMITS.fileTypes.join(',') });
  const fileHint = h('small', { class: 'muted' }, 'PNG 또는 JPEG, 2MB 이하 (선택)');

  const errors = {
    type: h('small', { class: 'field-error', hidden: true }),
    title: h('small', { class: 'field-error', hidden: true }),
    content: h('small', { class: 'field-error', hidden: true }),
    file: h('small', { class: 'field-error', hidden: true }),
  };
  const formError = h('p', { class: 'form-error', role: 'alert', hidden: true });
  const submit = h('button', { type: 'submit', class: 'primary' }, '보내기');

  function show(result) {
    for (const [key, el] of Object.entries(errors)) {
      el.textContent = result.errors[key] ?? '';
      el.hidden = !result.errors[key];
    }
  }

  const form = h('form', {
    class: 'feedback-form',
    novalidate: true,
    onsubmit: async (e) => {
      e.preventDefault();
      const chosen = file.files?.[0] ?? null;
      const result = validateFeedback({ type: type.value, title: title.value, content: content.value, file: chosen });
      show(result);
      formError.hidden = true;
      if (!result.ok) return;
      submit.disabled = true;
      try {
        const created = await api.createFeedback({
          data: {
            type: type.value, title: title.value.trim(), content: content.value.trim(), puzzleId,
            screen: screenInfo({ width: innerWidth, height: innerHeight, pixelRatio: devicePixelRatio }) ?? undefined,
          },
          screenshot: chosen,
        });
        if (handle.cancelled) return;
        showDone(created);
      } catch (err) {
        formError.textContent = err.message;
        formError.hidden = false;
        submit.disabled = false;
      }
    },
  },
  h('label', { class: 'field', for: 'fb-type' }, h('span', { class: 'label' }, '유형'), type, errors.type),
  h('label', { class: 'field', for: 'fb-title' }, h('span', { class: 'label' }, '제목'), title, errors.title),
  h('label', { class: 'field', for: 'fb-content' }, h('span', { class: 'label' }, '내용'), content, counter, errors.content),
  h('label', { class: 'field', for: 'fb-file' }, h('span', { class: 'label' }, '스크린샷'), file, fileHint, errors.file),
  h('p', { class: 'muted' }, '이메일은 받지 않아요. 브라우저·화면 정보와 (퍼즐에서 왔다면) 퍼즐 번호가 자동으로 함께 전달돼요.'),
  formError, submit);

  const section = h('section', { class: 'feedback' },
    h('h1', {}, '문의하기'),
    user.type === 'GUEST' ? guestNotice() : null,
    form);
  root.append(section);

  function showDone(created) {
    clear(section).append(
      h('h1', {}, '문의가 접수됐어요'),
      h('p', {}, '소중한 의견 고마워요.'),
      created.replyAvailable
        ? h('p', { class: 'muted' }, '답변이 달리면 헤더의 "내 문의"에 알림이 떠요.')
        : guestNotice(),
      h('p', {}, h('a', { class: 'button primary', href: '#/' }, '퍼즐 풀러 가기'), ' ',
        created.replyAvailable ? h('a', { class: 'button', href: '#/my-feedback' }, '내 문의 보기') : null));
    ctx.refreshBadge?.();
  }
}

// ---------------- 내 문의 내역 ----------------

export async function mountMyFeedback(root, ctx, handle) {
  const user = auth.currentUser();
  if (!user) return needIdentity(root);
  if (user.type !== 'MEMBER') {
    root.append(h('section', {},
      h('h1', {}, '내 문의'),
      h('p', {}, '답변은 회원만 받을 수 있어요.'),
      guestNotice(),
      h('p', {}, h('a', { class: 'button', href: '#/feedback' }, '문의하기'))));
    return;
  }

  let data;
  try {
    data = await api.getMyFeedback();
  } catch (e) {
    if (!handle.cancelled) root.append(h('p', { class: 'notice' }, e.message));
    return;
  }
  if (handle.cancelled) return;

  const section = h('section', { class: 'my-feedback' }, h('h1', {}, '내 문의'),
    h('p', {}, h('a', { class: 'button primary', href: '#/feedback' }, '새 문의 쓰기')));
  root.append(section);
  if (!data.items.length) {
    section.append(h('p', { class: 'muted' }, '아직 남긴 문의가 없어요.'));
    return;
  }
  const list = h('div', { class: 'item-list' });
  section.append(list);
  // 첨부 이미지용 object URL은 화면을 떠날 때 모두 해제한다
  const urls = createObjectUrlTracker();
  handle.cleanup = () => urls.revokeAll();
  data.items.forEach((item) => list.append(feedbackCard(item, ctx, handle, urls)));
}

function feedbackCard(item, ctx, handle, urls) {
  const unread = item.replies.filter((r) => r.unread).length;
  const replyBox = h('div', { class: 'replies' });
  const renderReplies = () => {
    clear(replyBox);
    item.replies.forEach((r) => replyBox.append(
      h('div', { class: `reply${r.unread ? ' unread' : ''}` },
        h('div', { class: 'muted' }, `관리자 답변 · ${formatDateTime(r.createdAt)}`, r.unread ? h('span', { class: 'new' }, ' NEW') : null),
        h('div', { class: 'content' }, r.content))));
  };
  renderReplies();

  const summaryText = item.replies.length
    ? `답변 ${item.replies.length}개${unread ? ` (새 답변 ${unread})` : ''}`
    : '아직 답변이 없어요';
  const details = h('details', { class: 'reply-details' }, h('summary', {}, summaryText), replyBox);
  if (item.replies.length) {
    // 답변을 펼쳐서 열람하면 읽음 처리한다
    details.addEventListener('toggle', async () => {
      if (!details.open) return;
      for (const r of item.replies.filter((x) => x.unread)) {
        try {
          const updated = await api.markReplyRead(r.id);
          Object.assign(r, updated);
        } catch {
          return; // 다음에 열 때 다시 시도한다
        }
      }
      if (handle.cancelled) return;
      renderReplies();
      details.querySelector('summary').textContent = `답변 ${item.replies.length}개`;
      ctx.refreshBadge?.();
    });
  }

  const attachmentBox = h('div', {});
  const attachmentButton = item.hasAttachment
    ? h('button', {
      type: 'button',
      onclick: async () => {
        attachmentButton.disabled = true;
        try {
          const url = urls.create(await api.getAttachmentBlob(item.id));
          if (handle.cancelled) {
            urls.revoke(url);
            return;
          }
          clear(attachmentBox).append(h('img', { class: 'screenshot', src: url, alt: '첨부한 스크린샷' }));
          attachmentButton.hidden = true;
        } catch (e) {
          ctx.toast(e.message);
          attachmentButton.disabled = false;
        }
      },
    }, '스크린샷 보기')
    : null;

  return h('article', { class: 'feedback-card' },
    h('div', { class: 'row' },
      h('span', { class: 'badge' }, labelOf(TYPES, item.type)),
      h('span', { class: `badge status-${item.status.toLowerCase()}` }, STATUS_LABEL[item.status]),
      h('span', { class: 'muted' }, formatDateTime(item.createdAt))),
    h('strong', {}, item.title),
    item.wordKorean
      ? h('div', { class: 'muted' }, `신고한 단어: ${item.wordKorean}${item.reason ? ` · ${labelOf(REASONS, item.reason)}` : ''}`
        + `${item.puzzleId ? ` (퍼즐 ${item.puzzleId})` : ''}`)
      : null,
    item.content ? h('div', { class: 'content' }, item.content) : null,
    attachmentButton, attachmentBox,
    details);
}

