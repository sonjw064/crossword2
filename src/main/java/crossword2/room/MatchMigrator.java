package crossword2.room;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import crossword2.auth.GuestDataMigrator;
import crossword2.auth.Owner;

/** 게스트 → 회원 이전: 대련 참가 기록의 소유자를 회원으로 바꾼다. */
@Component
public class MatchMigrator implements GuestDataMigrator {

	private final MatchParticipantRepository participants;

	public MatchMigrator(MatchParticipantRepository participants) {
		this.participants = participants;
	}

	@Override
	@Transactional
	public void migrate(Owner guest, Owner member) {
		participants.reassignOwner(guest.type(), guest.id(), member.type(), member.id());
	}
}
