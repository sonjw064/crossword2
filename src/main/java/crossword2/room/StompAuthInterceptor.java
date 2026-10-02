package crossword2.room;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import crossword2.auth.Owner;
import crossword2.auth.RateLimiter;
import crossword2.common.ApiException;

/**
 * 웹소켓(STOMP) 메시지의 인증과 인가.
 * <ul>
 * <li>CONNECT: {@code Authorization: Bearer ...} 헤더의 JWT를 기존 검증기로 확인한다(이전된 게스트 토큰도 거부).
 *     브라우저의 WebSocket은 헤더를 못 붙이므로 HTTP 핸드셰이크가 아니라 이 단계에서 인증한다.</li>
 * <li>그 외 모든 메시지: 인증되었는지, 토큰이 만료되지 않았는지, 세션이 한도보다 빠르게 보내지 않는지 확인한다.</li>
 * <li>SEND는 {@code /app/rooms/**}만, SUBSCRIBE는 내 개인 큐와 접근 가능한 방의 토픽만 허용한다.</li>
 * </ul>
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

	private static final Duration RATE_WINDOW = Duration.ofSeconds(10);
	private static final String BEARER = "Bearer ";

	private final JwtDecoder jwtDecoder;
	private final RoomRegistry registry;
	private final RateLimiter rateLimiter;
	private final RoomProperties props;
	private final Clock clock;

	public StompAuthInterceptor(JwtDecoder jwtDecoder, RoomRegistry registry, RateLimiter rateLimiter,
			RoomProperties props, Clock clock) {
		this.jwtDecoder = jwtDecoder;
		this.registry = registry;
		this.rateLimiter = rateLimiter;
		this.props = props;
		this.clock = clock;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (accessor == null || accessor.getCommand() == null) {
			return message; // 하트비트 등
		}
		StompCommand command = accessor.getCommand();
		if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
			accessor.setUser(authenticate(accessor));
		} else if (command == StompCommand.SEND || command == StompCommand.SUBSCRIBE) {
			RoomPrincipal principal = requireLiveUser(accessor);
			limitRate(accessor);
			if (command == StompCommand.SEND) {
				requireSendAllowed(accessor.getDestination());
			} else {
				requireSubscribeAllowed(accessor.getDestination(), principal.owner());
			}
		}
		return message;
	}

	private RoomPrincipal authenticate(StompHeaderAccessor accessor) {
		String header = accessor.getFirstNativeHeader("Authorization");
		if (header == null || !header.startsWith(BEARER)) {
			throw new MessagingException("authentication is required");
		}
		try {
			Jwt jwt = jwtDecoder.decode(header.substring(BEARER.length()).trim());
			return new RoomPrincipal(Owner.fromJwt(jwt), RoomController.nickname(jwt), jwt.getExpiresAt());
		} catch (JwtException | IllegalArgumentException e) {
			throw new MessagingException("the token is invalid or expired");
		}
	}

	private RoomPrincipal requireLiveUser(StompHeaderAccessor accessor) {
		if (!(accessor.getUser() instanceof RoomPrincipal principal)) {
			throw new MessagingException("authentication is required");
		}
		Instant expiresAt = principal.expiresAt();
		if (expiresAt == null || !clock.instant().isBefore(expiresAt)) {
			throw new MessagingException("the token has expired, please reconnect");
		}
		return principal;
	}

	private void limitRate(StompHeaderAccessor accessor) {
		try {
			rateLimiter.check("ws:" + accessor.getSessionId(), props.messagesPer10Seconds(), RATE_WINDOW);
		} catch (ApiException e) {
			throw new MessagingException("too many messages");
		}
	}

	private static void requireSendAllowed(String destination) {
		if (destination == null || !destination.startsWith("/app/rooms/")) {
			throw new MessagingException("destination not allowed");
		}
	}

	/** 내 개인 큐, 그리고 존재하는 방 중 내가 참가자이거나 아직 입장 가능한(대기 중인) 방의 토픽만 구독할 수 있다. */
	private void requireSubscribeAllowed(String destination, Owner owner) {
		if (destination == null) {
			throw new MessagingException("destination not allowed");
		}
		if (destination.equals("/user/queue/me") || destination.equals("/user/queue/errors")
				|| destination.equals("/user/queue/submit")) {
			return;
		}
		if (destination.startsWith(RoomService.TOPIC)) {
			String code = RoomCodes.normalize(destination.substring(RoomService.TOPIC.length()));
			Optional<Room> room = registry.find(code);
			if (room.isPresent() && (room.get().contains(owner) || room.get().status() == RoomStatus.WAITING)) {
				return;
			}
		}
		throw new MessagingException("destination not allowed");
	}
}
