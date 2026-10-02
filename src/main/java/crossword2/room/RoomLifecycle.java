package crossword2.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** 웹소켓 연결이 끊기면 참가자를 "연결 끊김"으로 표시하고, 주기적으로 유예 시간이 지난 자리와 빈 방을 정리한다. */
@Component
class RoomLifecycle {

	private static final Logger log = LoggerFactory.getLogger(RoomLifecycle.class);

	private final RoomService rooms;

	RoomLifecycle(RoomService rooms) {
		this.rooms = rooms;
	}

	@EventListener
	void onDisconnect(SessionDisconnectEvent event) {
		rooms.onDisconnect(event.getSessionId());
	}

	@Scheduled(fixedDelay = 1_000, initialDelay = 1_000)
	void sweep() {
		try {
			rooms.sweep();
		} catch (RuntimeException e) {
			log.warn("Room sweep failed", e);
		}
	}
}
