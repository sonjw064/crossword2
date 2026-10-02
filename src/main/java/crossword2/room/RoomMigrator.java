package crossword2.room;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import crossword2.auth.GuestDataMigrator;
import crossword2.auth.Owner;

/**
 * 게스트가 회원으로 이전되면 방(서버 메모리)에서 그 게스트를 내보낸다. 옛 게스트 토큰은 이전 후 거부되므로 방 안의
 * 자리가 주인 없이 남지 않게 하기 위해서다. 이전이 커밋된 뒤에만 실행한다(롤백되면 방에 그대로 남는다).
 */
@Component
public class RoomMigrator implements GuestDataMigrator {

	private final RoomService rooms;

	public RoomMigrator(RoomService rooms) {
		this.rooms = rooms;
	}

	@Override
	public void migrate(Owner guest, Owner member) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					rooms.kick(guest);
				}
			});
		} else {
			rooms.kick(guest);
		}
	}
}
