# SPEC.md — 영어 십자말풀이 (운영 버전)

> 한국어 뜻 힌트를 보고 영어 단어를 입력해 십자말풀이를 푸는 학습 웹 서비스.
> Claude Code(메인 개발)와 Codex(테스트/리뷰)로 개발하며, 이 문서가 작업 기준이다.
> 작업 전 반드시 이 문서와 `AGENTS.md`를 읽을 것. 구현 순서는 "8. 개발 단계"를 따른다.

---

## 1. 목표와 범위

- 실제 배포 및 운영까지 고려한 서비스
- 로그인 없이도(게스트) 대부분의 기능을 쓸 수 있어야 함
- 친구와 방 코드로 즐기는 실시간 대련 지원
- 사용자가 단어 오류를 신고하고 문의를 남길 수 있어야 함
- 정답은 서버에서만 관리하고 채점도 서버에서 수행 (클라이언트에 정답 노출 금지)

## 2. 기술 스택

| 영역 | 선택 |
|---|---|
| 백엔드 | Spring Boot 4.x, Java 21, Gradle |
| DB | PostgreSQL (개발 초기에는 H2 허용) |
| ORM | Spring Data JPA |
| 인증 | Spring Security + JWT (게스트 토큰 포함) |
| 실시간 | Spring WebSocket + STOMP |
| 프론트 | HTML/CSS/JS로 시작, 필요 시 React로 이전 |
| 테스트 | JUnit 5, Mockito, Spring Boot Test |
| 배포 | Docker + Nginx + HTTPS (VPS) |

## 3. 핵심 원칙

1. 그리드 생성과 채점은 **서버**에서 한다.
2. 클라이언트에는 칸 구조, 번호, 한국어 힌트만 내려준다. 풀이 중에는 정답 단어를 내려주지 않는다.
   - **정답 단어가 응답에 담기는 경우는 딱 두 가지**: (a) 해당 풀이 세션에서 맞힌 단어의 단어 카드(`GET /api/words/{id}`), (b) 사용자가 **정답 보기(포기)**를 선택해 세션이 종료된 뒤의 `reveal` 응답. 정답 보기는 정의상 정답을 보여주는 기능이므로 이 응답이 정답 공개의 유일한 경로다.
   - 그 외 모든 응답(퍼즐 조회, 채점, 첫 글자 힌트, 정의 힌트)에는 정답 단어를 포함하지 않는다. 첫 글자 힌트는 한 글자만 공개한다.
   - `reveal`은 한 번만 가능하며 호출 즉시 세션이 `GAVE_UP`으로 종료되어 이후 채점/힌트는 불가하다(409).
3. 그리드 생성기는 Spring과 무관한 **순수 Java 클래스**로 분리하고 seed를 받는다 (같은 입력 → 같은 결과).
4. 퍼즐은 요청 시 즉석 생성하지 않고 **미리 생성해 DB에 저장**한 뒤 조회한다.
5. API 경계를 처음부터 분리한다 (`/api/...`). 프론트는 API만 호출한다.
6. 모든 사용자 입력은 서버에서 검증하고 출력 시 이스케이프한다.

## 4. 기능 명세

### 4.1 솔로 플레이
- 난이도/주제/그리드 크기 선택 후 퍼즐 시작
- 가로/세로 힌트 목록(한국어 뜻), 칸 선택 시 해당 단어 하이라이트
- 입력: 키보드 및 모바일 터치/가상 키보드 대응
- 기능: 정답 체크, 힌트(첫 글자 공개), 정답 보기(포기), 타이머
- **정의 힌트**: 막혔을 때 힌트 버튼으로 해당 단어의 사전적 정의를 펼쳐 볼 수 있음 (**솔로/학습 모드 전용**)
  - 한국어 뜻은 기본 힌트로 항상 표시, 정의는 힌트 버튼을 눌렀을 때만 열림
  - 정의 힌트를 쓴 단어는 힌트 사용 수에 반영하고 오답노트 복습 대상에 포함
- **단어 카드**: 단어를 맞힌 직후(또는 정답 보기로 공개된 단어) 하단에 작은 카드로 표시
  - 구성: 영어 단어 → 한국어 뜻 → (품사) 사전적 정의
  - 풀이 중에는 아직 맞히지 않은 단어의 카드/정의를 조회할 수 없음 (서버에서 차단)
- **단어 뜻 표시 설정**: 표시 방식 3가지 — 끔 / 뜻만 / 뜻+정의
  - 게스트는 브라우저에, 회원은 서버에 저장 (기기 변경 시 유지)
- 완료 시 결과 화면 (소요 시간, 힌트 사용 수, 오답 수)

### 4.2 학습
- 오답노트: 틀리거나 힌트를 쓴 단어를 기록, 복습 모드 제공
- 진도 저장: 푼 퍼즐, 기록 (게스트는 서버에 게스트 ID로 저장)
- 단어 상세: 발음(TTS), 예문 표시, 단어 카드(뜻+정의) 복습
- 결과 화면: 푼 단어 전체를 단어 카드 목록으로 복습

### 4.3 계정
- **게스트**: 닉네임 입력 → 서버가 게스트 ID(UUID) 발급 → 브라우저에 저장해 유지
- **회원**: 회원가입/로그인
- **게스트 → 회원 이전**: 가입/로그인 시 게스트 ID를 함께 전달하면 서버가 해당 게스트의 기록(진도, 오답노트, 대련 기록, **문의**)을 회원 계정으로 이전
  - 이전은 게스트 ID당 **한 번만** 가능 (이전 완료 표시)
  - 다른 기기/브라우저의 게스트 데이터는 연결 불가 (가입 화면에 안내)
- 랭킹과 레이팅은 회원만 반영, 게스트는 친선전만

### 4.4 대련 (실시간)
- 게스트도 이용 가능
- **방 만들기**: 6자리 코드 발급 (혼동되는 `0/O`, `1/I` 제외)
  - 방장이 난이도, 주제, 그리드 크기, 제한 시간, 최대 인원(2~8명) 설정
- **방 입장**: 코드 직접 입력 또는 초대 링크(`/room/{code}`)
- **대기실**: 참가자 목록, 준비 완료, 방장의 시작
- 방장 이탈 시 다른 참가자에게 방장 위임, 비어 있는 방은 일정 시간 후 자동 삭제
- 연결이 끊겨도 일정 시간 안에 같은 게스트/회원 ID로 재접속하면 이어서 진행
- **모드** (구현 순서대로)
  1. 레이스: 같은 퍼즐을 풀어 먼저 완성하거나 점수가 높은 사람이 승리
  2. 단어 선점: 한 단어를 먼저 맞힌 사람이 점수를 가져감
  3. 협동: 같은 보드를 함께 채움
- 상대에게는 **진행률/점수만** 공개 (정답 및 입력 내용 비공개)
- **학습 보조 기능 사용 불가**: 정의 힌트 버튼과 풀이 중 단어 카드는 대련에서 비활성화
  (서버가 대련 퍼즐의 정의 힌트 요청을 거부). 단어 카드 복습은 **경기 종료 후 결과 화면에서만** 허용

### 4.5 문의 및 피드백
- 유형: 버그 신고, 단어/뜻 오류 신고, 기능 제안, 일반 문의
- 입력: 제목, 내용, 스크린샷(선택). **이메일 입력은 받지 않음**
- 자동 첨부: 작성자 ID(게스트 포함), 브라우저/기기 정보, 화면 정보, 퍼즐 ID
- **퍼즐 내 빠른 신고**: 힌트 옆 신고 버튼 → 단어 ID와 퍼즐 ID 자동 포함, 사유 선택
  (뜻이 틀림 / 정답이 여러 개 / 오타 / 기타)
- **게스트**: 작성 가능, 답변 수신 불가
  - 작성 화면 및 제출 완료 화면에 "회원가입하면 이 문의의 답변을 받을 수 있어요" 안내 표시
  - 응답에 `replyAvailable: false` 포함
- **회원**: 작성 가능, 답변은 **"내 문의 내역"** 화면에서 확인
  - 답변이 있으면 메뉴에 읽지 않음 뱃지 표시, 열람 시 읽음 처리(`readAt`)
- 게스트 문의가 회원으로 이전되면 이후 답변 가능
- 게스트 오래된 문의는 90일 후 삭제 또는 익명화
- 스팸 방지: IP/ID당 작성 횟수 제한(Rate limit), 길이 제한, 첨부 형식/용량 제한

### 4.6 관리자
- 단어 관리: 추가/수정/삭제, 일괄 등록(CSV/JSON), 난이도/주제 지정
- 문의 관리: 목록 조회(유형/상태/날짜 필터), 상태 변경(접수 → 확인 중 → 처리 완료/반려)
- 답변 작성: 회원 문의에만 가능, 게스트 문의는 이전 전까지 답변 버튼 비활성화
- 단어 오류 신고는 해당 단어 수정 화면으로 바로 이동
- 일일 퍼즐 관리, 사용 통계(자주 틀리는 단어 등)

### 4.7 운영 기능
- 일일 퍼즐: 모든 사용자가 같은 퍼즐을 푸는 하루 한 판
- 랭킹: 회원 대상 (일일 퍼즐 기록, 대련 승패)
- 통계: 단어별 정답률, 퍼즐별 완료율

## 5. 그리드 생성 알고리즘 요구사항

- 입력: 단어 목록, 그리드 크기, seed
- 출력: 배치 결과(단어, 시작 좌표, 방향, 번호)와 빈칸 구조
- 방식: 교차 배치 + 백트래킹 (확정적 seed 기반 셔플)
- 규칙
  - 인접한 단어가 의도치 않게 붙어 새로운 글자열을 만들지 않을 것
  - 모든 단어는 최소 하나 이상의 다른 단어와 교차 (연결된 그리드)
  - 가로/세로 번호는 좌상단부터 순서대로 부여
- 품질 점수: 배치된 단어 비율(기본 50% 이상), 교차 수(배치 단어당 교차 칸 수 `minCrossingRatio`, 기본 0.8, 기준값은 내림). 기준 미달 시 재시도 (**제한 시간/횟수 상한 필수**)
  - 연결된 그리드는 교차 수가 항상 (단어 수 - 1) 이상이다. 기본값 0.8은 이 하한을 지키는 퇴보 방지 기준이고, 1.0 이상이면 순환(루프)이 있는 그리드를 요구한다. 순환을 요구하면 작은 그리드(7×7)는 생성 성공률이 크게 떨어진다.
  - **결정성**: 탐색 범위는 노드 수로 제한한다. 시간 제한(TIMEOUT)은 안전장치이며, 초과하면 다른 seed로 대체하지 않고 실패로 처리한다(같은 입력 → 같은 결과).
- 테스트 필수 항목
  - 같은 seed → 같은 결과
  - 모든 배치 단어가 규칙을 만족하는지 검증하는 검증기(validator)
  - 단어가 너무 적거나 교차 불가능한 입력의 실패 처리
  - 성능 상한 (최대 크기에서 제한 시간 내 완료)

## 6. 도메인 모델 (초안)

- `Word`: id, english, korean(퍼즐용 한국어 뜻, 단어당 하나로 고정), partOfSpeech, definition(사전적 정의), difficulty, topic, example, active
- `Puzzle`: id, size, seed, difficulty, topic(null이면 전체 주제), wordCount, createdAt, type(NORMAL/DAILY)
- `PuzzleEntry`: id, puzzleId, wordId, startRow, startCol, direction, number
- `PlaySession`: id(UUID), puzzleId, startedAt, finishedAt, status(IN_PROGRESS/COMPLETED/GAVE_UP), wrongCount, 맞힌 항목·힌트 사용 항목 (1단계는 익명 세션, 2단계에서 소유자(ownerType/ownerId)를 붙여 `PlayRecord`로 연결)
- `User`: id, email, passwordHash, nickname, role, createdAt
- `GuestAccount`: id(UUID), nickname, createdAt, migratedToUserId(nullable)
- `PlayRecord`: id, puzzleId, ownerType, ownerId, elapsedSec, hintCount, wrongCount, completedAt
- `WrongAnswer`: id, ownerType, ownerId, wordId, count, lastAt
- `Room` (메모리 관리): code, hostId, settings, status, players
- `MatchResult`: id, roomCode, puzzleId, mode, results, endedAt
- `Feedback`: id, type, title, content, status, authorType, authorId, puzzleId, wordId, deviceInfo, createdAt
- `FeedbackReply`: id, feedbackId, adminId, content, createdAt, readAt
- `FeedbackAttachment`: id, feedbackId, storedPath, contentType, size

> `ownerType`: GUEST / MEMBER. 게스트 이전 시 `ownerType`과 `ownerId`를 회원으로 갱신한다.

## 7. API 초안

**인증**
- `POST /api/auth/guest` 게스트 생성 및 토큰 발급
- `POST /api/auth/signup`, `POST /api/auth/login` (게스트 ID 포함 시 기록 이전)

**퍼즐** (풀이 관련 요청은 `X-Play-Session` 헤더에 세션 ID를 담는다)
- `GET /api/puzzles/options` 선택 가능한 난이도/주제/크기 (실제 존재하는 퍼즐 기준)
- `GET /api/puzzles?difficulty&topic&size&page&pageSize` 목록, `GET /api/puzzles/{id}` 구조와 한국어 힌트만 반환 (`grid`: '.'=입력 칸, '#'=막힌 칸, 항목에 `wordId` 포함 — 단어 카드 조회용이며 정답은 아님)
- `POST /api/puzzles/{id}/start` 풀이 세션 생성 → `sessionId`
- `POST /api/puzzles/{id}/check` 채점 (항목별 CORRECT/WRONG/INCOMPLETE, 글자 수가 같은 오답만 오답 수 집계, 한 요청에 같은 `entryId`가 중복되면 400 `DUPLICATE_ENTRY`)
- `POST /api/puzzles/{id}/hint` (첫 글자 공개), `POST /api/puzzles/{id}/reveal` (정답 보기 = 포기, 세션 종료, 전체 정답과 단어 카드 반환 — 3장 정책 참고)
- `POST /api/puzzles/{id}/definition-hint` 정의 힌트 (솔로 전용, 대련 퍼즐은 403 — 대련 구현 시 적용)
- `GET /api/puzzles/daily`
- 종료된 세션에 대한 요청은 409 `SESSION_FINISHED`, 세션 누락/불일치는 400, 오류 본문은 `{code, message}`
- 같은 세션의 상태 변경 요청(채점/힌트/정답 보기)은 서버가 세션 행 잠금으로 직렬화한다. 잠금 대기 초과 등 충돌 시 409 `CONCURRENT_UPDATE`이며 같은 요청을 다시 보내면 된다.
- 퍼즐 생성 풀 규칙: 난이도는 "이하"(EASY=쉬움, MEDIUM=쉬움+보통, HARD=전체), 주제는 선택

**학습/기록**
- `GET /api/me/progress`, `GET /api/me/wrong-answers`
- `GET /api/words/{id}` 단어 카드 (`X-Play-Session` 필요, 해당 세션에서 맞힌/공개된 단어 또는 종료된 세션의 단어만 조회 가능, 아니면 403)
- `GET/PUT /api/me/settings` 단어 뜻 표시 설정 (끔/뜻만/뜻+정의)

**대련**
- `POST /api/rooms` 방 생성, `GET /api/rooms/{code}` 방 정보
- WebSocket(STOMP) 이벤트: `JOIN`, `READY`, `START`, `SUBMIT_WORD`, `SCORE_UPDATE`, `END`, `LEAVE`, `RECONNECT`

**문의**
- `POST /api/feedback` (게스트 허용, `replyAvailable` 반환)
- `GET /api/feedback/mine` (로그인 필수, 게스트는 401)
- `PATCH /api/feedback/replies/{id}/read`
- `GET /api/admin/feedback`, `PATCH /api/admin/feedback/{id}`, `POST /api/admin/feedback/{id}/reply`

**관리자**
- `/api/admin/words` CRUD, 일괄 등록, 일일 퍼즐 관리, 통계 조회

## 8. 개발 단계

작업은 아래 순서로 진행하며, 단계별로 브랜치를 나누고 작은 단위로 커밋한다.

**1단계: MVP (솔로 플레이)**
1. 프로젝트 세팅, `AGENTS.md`/`CLAUDE.md` 작성
2. `Word` 엔티티, 시드 데이터
3. 그리드 생성기 + 단위 테스트
4. 퍼즐 저장/조회/채점/힌트 API
5. 프론트 연동 (풀이 화면, 결과 화면)

**2단계: 계정과 문의**
1. 게스트 계정, 회원가입/로그인, JWT
2. 게스트 → 회원 기록 이전
3. 진도 저장, 오답노트
4. 문의/피드백 작성, 퍼즐 내 신고, 내 문의 내역, 읽지 않음 뱃지

**3단계: 대련**
1. 방 생성/입장(코드, 링크), 대기실
2. 레이스 모드, 재접속 처리
3. 단어 선점 모드
4. 협동 모드 (선택)

**4단계: 운영**
1. 관리자 단어 관리, 문의 처리
2. 일일 퍼즐, 랭킹, 통계
3. Docker, Nginx, HTTPS 배포, 로깅/모니터링, 백업

## 9. 비기능 요구사항

- **보안**: 비밀번호 해시(BCrypt), JWT 만료/갱신, CORS 제한, Rate limit(문의, 로그인, 방 생성), 입력 검증과 XSS 방지, 업로드 파일은 실행 불가 저장소에 보관
- **개인정보**: 문의에서 이메일을 받지 않음, 게스트 데이터 보관 기간 정책 적용
- **성능**: 퍼즐 조회는 미리 생성된 데이터로 응답, 그리드 생성은 배치/관리자 요청으로 수행
- **테스트**: 그리드 생성기와 채점 로직은 단위 테스트 필수, 주요 API는 통합 테스트
- **확장성**: 방 상태는 초기에는 서버 메모리(`ConcurrentHashMap`), 서버가 늘어나면 Redis로 이전

## 10. 도구 협업 규칙 (Claude Code + Codex)

- 규칙 파일은 `AGENTS.md`를 원본으로 두고 `CLAUDE.md`는 이를 참조한다.
- `AGENTS.md`에 **Spring Boot 4.x / Java 21 기준**임을 명시한다 (3.x 방식 코드 혼입 방지).
- Claude Code가 기능을 브랜치에서 구현하고, Codex가 테스트 추가와 코드 리뷰를 담당한다.
- 같은 파일을 두 도구가 동시에 수정하지 않는다. Git 커밋과 이 문서가 인수인계 기준이다.
- 작업 목록은 `TODO.md`에 관리하고, 기능을 변경하면 이 문서도 함께 갱신한다.

## 11. 미정 사항

- 단어 데이터 출처 (직접 작성 / 공개 단어장 / AI 생성 후 검수)
- 사전적 정의 출처: 저작권 문제로 사전 문장을 그대로 복사하지 않음. 개방형 사전(라이선스 확인) 또는 AI 생성 후 검수한 짧고 쉬운 설명 사용 검토
- 프론트 프레임워크 이전 시점 (HTML/CSS/JS 유지 vs React)
- 레이팅 산정 방식
- 단어 선점 모드의 점수 규칙
