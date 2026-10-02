package crossword2.room;

import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 핸드셰이크 때의 접속 주소를 세션 속성에 담는다(입장 시도 Rate limit용). 주소는 서버 설정의 신뢰 프록시 규칙을 거친
 * {@code getRemoteAddr()}만 쓰고 전달 헤더를 직접 읽지 않는다.
 */
class HandshakeIpInterceptor implements HandshakeInterceptor {

	static final String IP_ATTRIBUTE = "ip";

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
			Map<String, Object> attributes) {
		if (request instanceof ServletServerHttpRequest servlet) {
			attributes.put(IP_ATTRIBUTE, servlet.getServletRequest().getRemoteAddr());
		}
		return true;
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
			Exception exception) {
	}
}
