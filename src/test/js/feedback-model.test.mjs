import test from 'node:test';
import assert from 'node:assert/strict';
import * as F from '../../main/resources/static/js/feedback-model.js';

const valid = { type: 'BUG', title: '제목', content: '내용', file: null };
const png = (size = 1000) => ({ type: 'image/png', size });

test('올바른 입력은 통과한다', () => {
  assert.deepEqual(F.validateFeedback(valid), { ok: true, errors: {} });
});

test('유형, 제목, 내용은 필수이고 공백만으로는 안 된다', () => {
  assert.ok(F.validateFeedback({ ...valid, type: '' }).errors.type);
  assert.ok(F.validateFeedback({ ...valid, type: 'NOPE' }).errors.type);
  assert.ok(F.validateFeedback({ ...valid, title: '   ' }).errors.title);
  assert.ok(F.validateFeedback({ ...valid, content: '\n  \n' }).errors.content);
  assert.ok(F.validateFeedback({ ...valid, title: undefined }).errors.title);
});

test('길이 한도: 제목 100자, 내용 2000자 (경계값 포함)', () => {
  assert.equal(F.validateFeedback({ ...valid, title: '가'.repeat(100) }).ok, true);
  assert.ok(F.validateFeedback({ ...valid, title: '가'.repeat(101) }).errors.title);
  assert.equal(F.validateFeedback({ ...valid, content: '가'.repeat(2000) }).ok, true);
  assert.ok(F.validateFeedback({ ...valid, content: '가'.repeat(2001) }).errors.content);
});

test('앞뒤 공백은 글자 수에 넣지 않는다', () => {
  assert.equal(F.validateFeedback({ ...valid, title: ` ${'가'.repeat(100)} ` }).ok, true);
});

test('제어 문자: 제목은 줄바꿈도 불가, 내용은 줄바꿈과 탭만 허용', () => {
  assert.ok(F.validateFeedback({ ...valid, title: '첫줄\n둘째줄' }).errors.title);
  assert.ok(F.validateFeedback({ ...valid, content: '널\u0000문자' }).errors.content);
  assert.equal(F.validateFeedback({ ...valid, content: '첫줄\n둘째줄\t탭' }).ok, true);
});

test('스크린샷은 선택이고, PNG/JPEG 2MB 이하만 허용', () => {
  assert.equal(F.validateFeedback({ ...valid, file: null }).ok, true);
  assert.equal(F.validateFeedback({ ...valid, file: png() }).ok, true);
  assert.equal(F.validateFeedback({ ...valid, file: { type: 'image/jpeg', size: F.LIMITS.fileBytes } }).ok, true);
  assert.ok(F.validateFeedback({ ...valid, file: { type: 'image/gif', size: 10 } }).errors.file);
  assert.ok(F.validateFeedback({ ...valid, file: { type: 'image/svg+xml', size: 10 } }).errors.file);
  assert.ok(F.validateFeedback({ ...valid, file: { type: 'application/pdf', size: 10 } }).errors.file);
  assert.ok(F.validateFeedback({ ...valid, file: png(F.LIMITS.fileBytes + 1) }).errors.file);
});

test('오류는 필드별로 모두 모아서 준다', () => {
  const { ok, errors } = F.validateFeedback({ type: '', title: '', content: '', file: { type: 'image/gif', size: 1 } });
  assert.equal(ok, false);
  assert.deepEqual(Object.keys(errors).sort(), ['content', 'file', 'title', 'type']);
});

test('unreadBadge: 0 이하는 숨기고 99를 넘으면 99+', () => {
  assert.equal(F.unreadBadge(0), '');
  assert.equal(F.unreadBadge(-3), '');
  assert.equal(F.unreadBadge(NaN), '');
  assert.equal(F.unreadBadge(undefined), '');
  assert.equal(F.unreadBadge(1), '1');
  assert.equal(F.unreadBadge(99), '99');
  assert.equal(F.unreadBadge(100), '99+');
  assert.equal(F.unreadBadge(2500), '99+');
});

test('screenInfo: 서버가 받는 형식으로 만들고, 이상한 값은 null', () => {
  assert.equal(F.screenInfo({ width: 1280, height: 720, pixelRatio: 1 }), '1280x720@1');
  assert.equal(F.screenInfo({ width: 390.4, height: 844.2, pixelRatio: 3 }), '390x844@3');
  assert.equal(F.screenInfo({ width: 800, height: 600, pixelRatio: 1.5 }), '800x600@1.5');
  assert.equal(F.screenInfo({ width: 800, height: 600, pixelRatio: 1.3333333 }), '800x600@1.33');
  assert.equal(F.screenInfo({ width: 800, height: 600 }), '800x600');
  assert.equal(F.screenInfo({ width: 800, height: 600, pixelRatio: 25 }), '800x600');
  assert.equal(F.screenInfo({ width: NaN, height: 600 }), null);
  assert.equal(F.screenInfo({ width: 0, height: 600 }), null);
  assert.equal(F.screenInfo(), null);
});

test('서버 형식과 일치한다 (화면 크기 정규식)', () => {
  const server = /^\d{2,5}x\d{2,5}(@\d(\.\d{1,2})?)?$/;
  for (const info of [F.screenInfo({ width: 390, height: 844, pixelRatio: 3 }), F.screenInfo({ width: 1920, height: 1080, pixelRatio: 1.25 })]) {
    assert.match(info, server);
  }
});

test('quickReportBody: 덧붙이는 말이 없으면 content를 보내지 않는다', () => {
  assert.deepEqual(F.quickReportBody({ puzzleId: 3, wordId: 7, reason: 'TYPO', comment: '  ', screen: '800x600' }),
    { type: 'WORD_ERROR', puzzleId: 3, wordId: 7, reason: 'TYPO', screen: '800x600' });
  assert.deepEqual(F.quickReportBody({ puzzleId: 3, wordId: 7, reason: 'OTHER', comment: ' 설명 ' }),
    { type: 'WORD_ERROR', puzzleId: 3, wordId: 7, reason: 'OTHER', content: '설명' });
});

test('선택지와 라벨', () => {
  assert.equal(F.TYPES.length, 4);
  assert.equal(F.REASONS.length, 4);
  assert.equal(F.labelOf(F.TYPES, 'BUG'), '버그 신고');
  assert.equal(F.labelOf(F.REASONS, 'WRONG_MEANING'), '뜻이 틀림');
  assert.equal(F.labelOf(F.TYPES, 'UNKNOWN'), 'UNKNOWN');
  assert.equal(F.STATUS_LABEL.IN_REVIEW, '확인 중');
});
