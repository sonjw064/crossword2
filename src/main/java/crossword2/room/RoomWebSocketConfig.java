package crossword2.room;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * 실시간 통신(STOMP over WebSocket). 엔드포인트는 /ws 이고 <b>같은 출처의 페이지만</b> 접속할 수 있다
 * (허용 출처를 따로 지정하지 않으면 Spring이 다른 출처의 핸드셰이크를 거부한다).
 */
@Configuration
@EnableWebSocketMessageBroker
public class RoomWebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private static final long HEARTBEAT_MS = 10_000;

	private final StompAuthInterceptor authInterceptor;
	private final RoomProperties props;

	public RoomWebSocketConfig(StompAuthInterceptor authInterceptor, RoomProperties props) {
		this.authInterceptor = authInterceptor;
		this.props = props;
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws").addInterceptors(new HandshakeIpInterceptor());
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		ThreadPoolTaskScheduler heartbeat = new ThreadPoolTaskScheduler();
		heartbeat.setPoolSize(1);
		heartbeat.setThreadNamePrefix("ws-heartbeat-");
		heartbeat.initialize();
		registry.setApplicationDestinationPrefixes("/app");
		registry.setUserDestinationPrefix("/user");
		registry.enableSimpleBroker("/topic", "/queue").setHeartbeatValue(new long[] { HEARTBEAT_MS, HEARTBEAT_MS })
				.setTaskScheduler(heartbeat);
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(authInterceptor);
	}

	@Override
	public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
		registration.setMessageSizeLimit(props.maxMessageBytes());
		registration.setSendBufferSizeLimit(512 * 1024);
		registration.setSendTimeLimit(10_000);
	}
}
