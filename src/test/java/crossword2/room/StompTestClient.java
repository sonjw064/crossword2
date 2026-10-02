package crossword2.room;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** 테스트용 STOMP 클라이언트. 받은 메시지는 구독한 목적지별 큐에 쌓인다. */
final class StompTestClient implements AutoCloseable {

	private static final long TIMEOUT_SECONDS = 5;

	private final WebSocketStompClient client;
	private final Map<String, BlockingQueue<Map<String, Object>>> inbox = new ConcurrentHashMap<>();
	private final BlockingQueue<String> transportErrors = new LinkedBlockingQueue<>();
	private volatile StompSession session;

	private StompTestClient() {
		client = new WebSocketStompClient(new StandardWebSocketClient());
		client.setMessageConverter(new JacksonJsonMessageConverter());
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.initialize();
		client.setTaskScheduler(scheduler);
		client.setDefaultHeartbeat(new long[] { 0, 0 });
	}

	/** 연결에 성공하면 클라이언트를, 실패하면 예외를 던진다. {@code token}이 null이면 Authorization 헤더 없이 접속한다. */
	static StompTestClient connect(int port, String token) throws Exception {
		return connect(port, token, new WebSocketHttpHeaders());
	}

	static StompTestClient connect(int port, String token, WebSocketHttpHeaders handshakeHeaders) throws Exception {
		StompTestClient wrapper = new StompTestClient();
		StompHeaders connectHeaders = new StompHeaders();
		if (token != null) {
			connectHeaders.add("Authorization", "Bearer " + token);
		}
		wrapper.session = wrapper.client.connectAsync("ws://localhost:" + port + "/ws", handshakeHeaders, connectHeaders,
				new StompSessionHandlerAdapter() {
					@Override
					public void handleTransportError(StompSession s, Throwable exception) {
						wrapper.transportErrors.add(String.valueOf(exception));
					}

					@Override
					public void handleException(StompSession s, org.springframework.messaging.simp.stomp.StompCommand command,
							StompHeaders headers, byte[] payload, Throwable exception) {
						wrapper.transportErrors.add(String.valueOf(exception));
					}
				}).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return wrapper;
	}

	boolean isConnected() {
		return session != null && session.isConnected();
	}

	/** 목적지를 구독하고 이후 메시지를 큐에 쌓는다. */
	void subscribe(String destination) {
		BlockingQueue<Map<String, Object>> queue = inbox.computeIfAbsent(destination, d -> new LinkedBlockingQueue<>());
		session.subscribe(destination, new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return Map.class;
			}

			@Override
			@SuppressWarnings("unchecked")
			public void handleFrame(StompHeaders headers, Object payload) {
				queue.add((Map<String, Object>) payload);
			}
		});
	}

	void send(String destination, Object payload) {
		StompHeaders headers = new StompHeaders();
		headers.setDestination(destination);
		session.send(headers, payload == null ? new byte[0] : payload);
	}

	/** 구독한 목적지의 다음 메시지를 기다린다. 시간 안에 오지 않으면 null. */
	Map<String, Object> next(String destination) throws InterruptedException {
		return inbox.get(destination).poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	Map<String, Object> next(String destination, long millis) throws InterruptedException {
		return inbox.get(destination).poll(millis, TimeUnit.MILLISECONDS);
	}

	/** 받은 메시지 중 조건에 맞는 첫 메시지를 기다린다(앞의 다른 메시지는 버린다). */
	Map<String, Object> nextMatching(String destination, java.util.function.Predicate<Map<String, Object>> test)
			throws Exception {
		long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
		while (System.currentTimeMillis() < deadline) {
			Map<String, Object> message = inbox.get(destination).poll(200, TimeUnit.MILLISECONDS);
			if (message != null && test.test(message)) {
				return message;
			}
		}
		throw new TimeoutException("no matching message on " + destination);
	}

	List<String> transportErrors() {
		return List.copyOf(transportErrors);
	}

	/** 연결이 끊길 때까지(서버가 닫을 때까지) 기다린다. */
	boolean awaitClosed() throws InterruptedException {
		long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
		while (System.currentTimeMillis() < deadline) {
			if (!isConnected()) {
				return true;
			}
			Thread.sleep(50);
		}
		return !isConnected();
	}

	void disconnect() {
		if (session != null && session.isConnected()) {
			session.disconnect();
		}
	}

	@Override
	public void close() {
		disconnect();
		client.stop();
	}
}
