package crossword2.puzzle;

import org.springframework.stereotype.Component;

import crossword2.auth.GuestDataMigrator;
import crossword2.auth.Owner;

/** 게스트가 회원이 되면 그 게스트의 풀이 세션을 회원 소유로 옮긴다. */
@Component
class PlaySessionMigrator implements GuestDataMigrator {

	private final PlaySessionRepository sessions;

	PlaySessionMigrator(PlaySessionRepository sessions) {
		this.sessions = sessions;
	}

	@Override
	public void migrate(Owner guest, Owner member) {
		sessions.reassignOwner(guest.type(), guest.id(), member.type(), member.id());
	}
}
