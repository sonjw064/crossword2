# AGENTS.md

영어 십자말풀이 웹 서비스. 작업 전 반드시 `SPEC.md`를 읽고, 구현 순서는 SPEC "8. 개발 단계"를 따른다.

## 기술 기준 (중요)
- **Spring Boot 4.x(현재 4.1.1) / Java 21 / Gradle** 기준이다. Spring Boot 3.x 방식 코드·의존성을 섞지 말 것.
  (예: 스타터 이름 `spring-boot-starter-webmvc`, 테스트 어노테이션 패키지 `org.springframework.boot.webmvc.test.autoconfigure`)
  불확실하면 추측하지 말고 공식 문서나 실제 빌드로 확인한다.
- 루트 패키지: `crossword2`
- DB: PostgreSQL (개발 초기 H2 허용), Spring Data JPA
- 인증: Spring Security OAuth2 Resource Server(JWT, HS256) + 교체형 refresh 토큰 / 실시간: Spring WebSocket + STOMP
- 프론트: HTML/CSS/JS (`src/main/resources/static`, 프레임워크·빌드 없음, API만 호출). 서버에서 온 문자열은 `textContent`로만 넣고 `innerHTML`은 쓰지 않는다. 화면과 무관한 로직은 `js/grid-model.js`에 순수 함수로 둔다.
- 테스트: JUnit 5, Mockito, Spring Boot Test

## 핵심 규칙
1. 그리드 생성과 채점은 서버에서만 한다. **API 응답에 정답 단어를 포함하지 않는다.**
   예외는 SPEC 3장에 정한 경로뿐이다: 세션에서 맞힌 단어의 카드, 정답 보기(`reveal`)로 세션을 종료한 뒤의 응답, 오답노트, 대련에서 경기 종료 후 결과 카드와 본인이 맞힌 단어(`solved`).
   새 응답을 추가할 때 이 외의 경로에 정답이 실리지 않는지 테스트로 확인한다.
2. 그리드 생성기(`crossword2.grid`)는 Spring 의존이 없는 순수 Java이며 seed 기반으로 결정적이어야 한다.
3. 퍼즐은 미리 생성해 DB에 저장하고 조회한다. 요청 시 즉석 생성 금지.
4. API는 `/api/...` 로 분리한다.
5. 모든 사용자 입력은 서버에서 검증하고 출력 시 이스케이프한다.
6. 대련 중(경기 진행 중인 방의 참가자)에는 솔로 풀이 기능 전체(세션 시작, 채점, 힌트, 정의 힌트, 정답 보기, 단어 카드)를 서버가 403으로 막는다(`PlayRestrictions`). `PlayService`에 솔로 기능을 추가하면 이 확인을 넣는다.
7. 풀이 세션(`PlaySession`)의 상태를 바꾸는 코드는 `findForUpdate`(행 잠금)로 세션을 읽는다. 잠금 없이 읽고 수정하지 않는다.
   잠금 순서는 항상 **계정(회원/게스트) 행 → 세션 행**이다(`AccountLock`). 게스트 이전은 게스트 계정 → 회원 계정 → 데이터 순서. 이 순서를 어기면 교착이 생긴다.
   사용자별 기록(`PlayRecord`, `WrongAnswer`)은 풀이 종료 시 `PlayFinished` 이벤트로 같은 트랜잭션에서 남긴다.
   소유자가 있는 새 기록(풀이 세션 시작, 문의 작성)을 만들 때는 먼저 `AccountLock.lockActive`로 계정을 잠그고 이전된 게스트/없는 계정이면 거부한다(게스트 이전과 경쟁해도 주인 없는 데이터가 생기지 않게).
   로딩해 둔 엔티티를 고쳐 쓰는 방식의 일괄 정리(익명화 등)는 다른 트랜잭션의 bulk update와 경쟁하면 최신 값을 덮어쓴다. 조건을 포함한 조건부 UPDATE로 처리한다.
8. 시크릿(JWT 키, DB 비밀번호)은 코드에 쓰지 않고 환경변수로 받는다.
9. 컨트롤러는 `@AuthenticationPrincipal Jwt`를 `Owner.fromJwt()`로 바꿔 쓴다(익명이면 null). 비밀번호는 BCrypt, refresh 토큰은 해시만 저장한다.
10. 클라이언트 IP는 `request.getRemoteAddr()`만 쓰고 `X-Forwarded-For` 등을 직접 읽지 않는다(프록시 신뢰는 prod 설정의 `native` 전략과 `TRUSTED_PROXIES`로만 처리). 관리자 권한은 JWT claim만 믿지 말고 DB의 현재 role을 확인한다.
11. 소유자(ownerType/ownerId)를 가지는 데이터 모델을 추가하면 반드시 `GuestDataMigrator` 구현체를 만들어 게스트 → 회원 이전에 포함시키고, 이전 테스트를 추가한다.
12. 문의(`Feedback`)의 작성자용 응답에는 영어 정답 단어를 넣지 않는다(`wordId`와 한국어 뜻만). 단어 신고는 풀이 도중에 하므로 정답이 새는 경로가 된다. 새 응답을 추가할 때 테스트로 확인한다.
13. 업로드 파일은 `ImageSanitizer`를 거쳐 재인코딩한 결과만 `AttachmentStore`(웹 루트 밖, 서버가 만든 이름)에 저장한다. 사용자가 준 파일명·Content-Type·원본 바이트를 그대로 저장하거나 서빙하지 않는다.
14. 응답에 CSP(`default-src 'self'`)가 붙는다. 프론트에서 인라인 스크립트나 `style` 속성(`setAttribute('style')`)을 쓰지 말고 `element.style.setProperty`를 쓴다.
15. 대련 방(`crossword2.room`)은 서버 메모리 상태다. `Room`은 Spring 의존이 없는 순수 클래스(synchronized)이고, 방 구조 변경(생성/삭제/한 사람 한 방)은 `RoomRegistry`가 맡는다. 다른 참가자에게 내려가는 `RoomView`/이벤트에 계정 ID(`Owner`)를 넣지 않는다(`playerId`와 닉네임만).
16. 웹소켓 메시지는 `StompAuthInterceptor`에서 인증·목적지·속도를 검사한다. 새 목적지를 추가하면 SEND/SUBSCRIBE 허용 규칙과 테스트를 함께 갱신하고, 명령 오류는 `/user/queue/errors`로만 돌려준다. 오류/응답에 정답 단어를 넣지 않는다.
17. 대련 경기 상태(`Match`)의 정답은 서버 메모리에만 있고 `toString`/공개 상태(`RoomView.MatchView`)에 싣지 않는다. 경기 결과를 저장하는 코드는 계정 행을 정해진 순서로 잠근 뒤 `AccountLock.lockCurrent`로 현재 소유자를 얻어 쓴다. 대련 데이터(`MatchParticipant`)는 `MatchMigrator`로 이전한다.

## 명령어
- 빌드/테스트: `./gradlew build`
- 테스트만: `./gradlew test`
- 프론트 로직 테스트(그리드 모델, 인증 상태): `node --test "src/test/js/*.test.mjs"` (Node 22+, npm 의존성 없음, Gradle 빌드와는 별개)
- 실행: `./gradlew bootRun` (기본 H2, 운영은 `--spring.profiles.active=prod` + `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`)

## 코드 스타일
- 패키지는 기능 단위(`grid`, `puzzle`, `word`, `common` ...)로 나눈다.
- 엔티티를 API 응답으로 직접 반환하지 말고 DTO를 쓴다.
- 그리드 생성기와 채점 로직은 단위 테스트 필수, 주요 API는 통합 테스트.

## 협업 규칙 (Claude Code + Codex)
- Claude Code: 기능 브랜치에서 구현. Codex: 테스트 추가와 코드 리뷰.
- 같은 파일을 두 도구가 동시에 수정하지 않는다.
- 단계별 브랜치, 작은 단위 커밋. 커밋과 `SPEC.md`가 인수인계 기준이다.
- 작업 목록은 `TODO.md`에 관리하고, 기능을 변경하면 `SPEC.md`도 함께 갱신한다.
- 요청 범위를 넘는 리팩터링이나 선행 구현을 하지 않는다.
