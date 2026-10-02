package crossword2.room;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 대련 설정. 방 상태는 서버 메모리에 있으므로 서버를 재시작하면 방이 사라진다(서버가 여러 대가 되면 Redis로 이전, SPEC 9장).
 *
 * @param maxRooms 동시에 존재할 수 있는 방 수의 상한
 * @param reconnectGrace 연결이 끊긴 참가자의 자리를 유지하는 시간(대기 중일 때)
 * @param emptyRoomTtl 연결된 참가자가 한 명도 없는 방을 삭제하기까지의 시간
 * @param messagesPer10Seconds 웹소켓 세션 하나가 10초 동안 보낼 수 있는 메시지 수
 * @param maxMessageBytes 웹소켓 메시지 한 건의 최대 크기
 * @param matchCountdown 경기를 시작한 뒤 제출을 받기 시작하기까지의 카운트다운
 * @param enabledModes 지금 선택할 수 있는 모드(구현된 모드만)
 */
@ConfigurationProperties("app.room")
public record RoomProperties(
		@DefaultValue("500") int maxRooms,
		@DefaultValue("60s") Duration reconnectGrace,
		@DefaultValue("5m") Duration emptyRoomTtl,
		@DefaultValue("10") int createPerHourPerOwner,
		@DefaultValue("30") int createPerHourPerIp,
		@DefaultValue("30") int joinPerMinutePerOwner,
		@DefaultValue("60") int joinPerMinutePerIp,
		@DefaultValue("100") int messagesPer10Seconds,
		@DefaultValue("16384") int maxMessageBytes,
		@DefaultValue("3s") Duration matchCountdown,
		@DefaultValue("RACE") Set<RoomMode> enabledModes) {
}
