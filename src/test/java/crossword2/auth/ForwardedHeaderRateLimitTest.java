package crossword2.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 실제 HTTP(Tomcat)로 클라이언트 IP 판단을 확인한다. 전달 헤더(X-Forwarded-For)는 신뢰하는 프록시가 보낼 때만 반영되어야 한다.
 * (서버 포트를 직접 열어 두거나 신뢰하지 않는 곳에서 온 헤더로 Rate limit을 우회할 수 없어야 한다.)
 */
class ForwardedHeaderRateLimitTest {

	private static int guestStatus(String port, String forwardedFor) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/guest"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"nickname\":\"헤더\"}"));
		if (forwardedFor != null) {
			builder.header("X-Forwarded-For", forwardedFor);
		}
		return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	/** 기본 설정(전달 헤더 전략 없음): 헤더를 보내도 연결 주소만 본다. */
	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
			properties = "app.rate-limit.guest-per-hour=2")
	class ByDefaultHeadersAreIgnored {

		@Value("${local.server.port}")
		String port;

		@Test
		void changingTheHeaderDoesNotResetTheLimit() throws Exception {
			assertThat(guestStatus(port, "203.0.113.1")).isEqualTo(201);
			assertThat(guestStatus(port, "203.0.113.2")).isEqualTo(201);
			assertThat(guestStatus(port, "203.0.113.3")).isEqualTo(429);
		}
	}

	/** native 전략이어도 신뢰 목록에 없는 곳(여기서는 loopback)에서 온 헤더는 무시한다. */
	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
			"app.rate-limit.guest-per-hour=2", "server.forward-headers-strategy=native",
			"server.tomcat.remoteip.internal-proxies=192\\.0\\.2\\.1" })
	class HeadersFromUntrustedPeersAreIgnored {

		@Value("${local.server.port}")
		String port;

		@Test
		void changingTheHeaderDoesNotResetTheLimit() throws Exception {
			assertThat(guestStatus(port, "203.0.113.1")).isEqualTo(201);
			assertThat(guestStatus(port, "203.0.113.2")).isEqualTo(201);
			assertThat(guestStatus(port, "203.0.113.3")).isEqualTo(429);
		}
	}

	/** 신뢰하는 프록시(loopback을 신뢰 목록에 넣음)가 보낸 헤더는 클라이언트 IP로 반영되어 사용자별로 제한된다. */
	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
			"app.rate-limit.guest-per-hour=2", "server.forward-headers-strategy=native",
			"server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1" })
	class HeadersFromTrustedProxiesIdentifyTheClient {

		@Value("${local.server.port}")
		String port;

		@Test
		void eachForwardedClientHasItsOwnLimit() throws Exception {
			assertThat(guestStatus(port, "198.51.100.1")).isEqualTo(201);
			assertThat(guestStatus(port, "198.51.100.1")).isEqualTo(201);
			assertThat(guestStatus(port, "198.51.100.1")).isEqualTo(429);
			assertThat(guestStatus(port, "198.51.100.2")).as("다른 클라이언트는 영향받지 않는다").isEqualTo(201);
		}

		@Test
		void aForgedLeftmostEntryDoesNotHelpWhenTheProxyAppendsTheRealAddress() throws Exception {
			// 프록시가 실제 주소를 오른쪽에 덧붙이는 경우: 가장 오른쪽의 (신뢰하지 않는) 주소가 클라이언트다
			assertThat(guestStatus(port, "10.9.9.1, 198.51.100.50")).isEqualTo(201);
			assertThat(guestStatus(port, "10.9.9.2, 198.51.100.50")).isEqualTo(201);
			assertThat(guestStatus(port, "10.9.9.3, 198.51.100.50")).isEqualTo(429);
		}
	}
}
