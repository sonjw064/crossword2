package crossword2.auth;

/**
 * 게스트가 회원으로 이전될 때, 게스트 소유 데이터를 회원 소유로 옮기는 확장 지점.
 * 소유자(ownerType/ownerId)를 가지는 새 데이터 모델은 반드시 이 인터페이스를 구현해 등록한다
 * (예: 풀이 세션, 풀이 기록, 오답노트, 문의, 대련 기록).
 * 모든 구현체는 이전 트랜잭션 안에서 호출되며, 하나라도 예외를 던지면 이전 전체(가입 포함)가 취소된다.
 */
public interface GuestDataMigrator {

	void migrate(Owner guest, Owner member);
}
