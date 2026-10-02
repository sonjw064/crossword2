# AGENTS.md

영어 십자말풀이 웹 서비스. 작업 전 반드시 `SPEC.md`를 읽고, 구현 순서는 SPEC "8. 개발 단계"를 따른다.

## 기술 기준 (중요)
- **Spring Boot 4.x(현재 4.1.1) / Java 21 / Gradle** 기준이다. Spring Boot 3.x 방식 코드·의존성을 섞지 말 것.
  (예: 스타터 이름 `spring-boot-starter-webmvc`, 테스트 어노테이션 패키지 `org.springframework.boot.webmvc.test.autoconfigure`)
  불확실하면 추측하지 말고 공식 문서나 실제 빌드로 확인한다.
- 루트 패키지: `crossword2`
- DB: PostgreSQL (개발 초기 H2 허용), Spring Data JPA
- 인증: Spring Security + JWT / 실시간: Spring WebSocket + STOMP
- 프론트: HTML/CSS/JS (`src/main/resources/static`, API만 호출)
- 테스트: JUnit 5, Mockito, Spring Boot Test

## 핵심 규칙
1. 그리드 생성과 채점은 서버에서만 한다. **API 응답에 정답 단어를 포함하지 않는다.**
   예외는 SPEC 3장에 정한 두 경로뿐이다: 세션에서 맞힌 단어의 카드, 정답 보기(`reveal`)로 세션을 종료한 뒤의 응답.
   새 응답을 추가할 때 이 외의 경로에 정답이 실리지 않는지 테스트로 확인한다.
2. 그리드 생성기(`crossword2.grid`)는 Spring 의존이 없는 순수 Java이며 seed 기반으로 결정적이어야 한다.
3. 퍼즐은 미리 생성해 DB에 저장하고 조회한다. 요청 시 즉석 생성 금지.
4. API는 `/api/...` 로 분리한다.
5. 모든 사용자 입력은 서버에서 검증하고 출력 시 이스케이프한다.
6. 대련 중에는 정의 힌트/단어 카드 조회를 서버가 차단한다.
7. 풀이 세션(`PlaySession`)의 상태를 바꾸는 코드는 `findForUpdate`(행 잠금)로 세션을 읽는다. 잠금 없이 읽고 수정하지 않는다.
8. 시크릿(JWT 키, DB 비밀번호)은 코드에 쓰지 않고 환경변수로 받는다.

## 명령어
- 빌드/테스트: `./gradlew build`
- 테스트만: `./gradlew test`
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
