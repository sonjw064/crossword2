package crossword2.room;

import java.security.Principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import crossword2.common.ApiException;
import crossword2.common.ApiExceptionHandler.ErrorResponse;
import crossword2.room.RoomDtos.JoinAck;
import crossword2.room.RoomDtos.ReadyCommand;
import crossword2.room.RoomDtos.RoomSettingsRequest;

/**
 * 대기실 명령(/app/rooms/{code}/...). 오류는 명령을 보낸 연결에게만 /user/queue/errors 로 돌려주고,
 * 성공한 변경은 {@link RoomService}가 방 전체에 방송한다.
 */
@Controller
class RoomMessageController {

	private static final Logger log = LoggerFactory.getLogger(RoomMessageController.class);

	private final RoomService rooms;

	RoomMessageController(RoomService rooms) {
		this.rooms = rooms;
	}

	@MessageMapping("/rooms/{code}/join")
	@SendToUser(destinations = "/queue/me", broadcast = false)
	JoinAck join(@DestinationVariable String code, Principal principal, SimpMessageHeaderAccessor headers) {
		RoomPrincipal user = RoomPrincipal.of(principal);
		Object ip = headers.getSessionAttributes() == null ? null : headers.getSessionAttributes().get(HandshakeIpInterceptor.IP_ATTRIBUTE);
		return rooms.join(code, user.owner(), user.nickname(), headers.getSessionId(), ip == null ? "unknown" : ip.toString());
	}

	/** 현재 방 상태를 요청한 연결에게만 돌려준다(방송 없음). 방 토픽을 구독한 직후, 그 사이 놓친 변경을 맞추는 데 쓴다. */
	@MessageMapping("/rooms/{code}/sync")
	@SendToUser(destinations = "/queue/me", broadcast = false)
	JoinAck sync(@DestinationVariable String code, Principal principal) {
		return rooms.sync(code, RoomPrincipal.of(principal).owner());
	}

	@MessageMapping("/rooms/{code}/ready")
	void ready(@DestinationVariable String code, Principal principal, ReadyCommand command) {
		rooms.ready(code, RoomPrincipal.of(principal).owner(), command.ready());
	}

	@MessageMapping("/rooms/{code}/leave")
	void leave(@DestinationVariable String code, Principal principal) {
		rooms.leave(code, RoomPrincipal.of(principal).owner());
	}

	@MessageMapping("/rooms/{code}/settings")
	void settings(@DestinationVariable String code, Principal principal, RoomSettingsRequest request) {
		rooms.updateSettings(code, RoomPrincipal.of(principal).owner(), request);
	}

	@MessageMapping("/rooms/{code}/start")
	void start(@DestinationVariable String code, Principal principal) {
		rooms.start(code, RoomPrincipal.of(principal).owner());
	}

	@MessageExceptionHandler
	@SendToUser(destinations = "/queue/errors", broadcast = false)
	ErrorResponse handleError(Exception e) {
		if (e instanceof ApiException api) {
			return new ErrorResponse(api.code(), api.getMessage());
		}
		if (e instanceof org.springframework.messaging.converter.MessageConversionException
				|| e instanceof org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException) {
			return new ErrorResponse("MALFORMED_REQUEST", "the message could not be understood");
		}
		log.warn("Unexpected error handling a room command", e);
		return new ErrorResponse("INTERNAL_ERROR", "something went wrong");
	}
}
