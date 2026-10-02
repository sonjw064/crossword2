package crossword2.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient.Tokens;

/** refresh 토큰 교체와 로그아웃이 동시에 와도 토큰이 살아남거나 두 번 발급되지 않는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class RefreshConcurrencyTest {

	@Autowired
	MockMvc mvc;

	private MockHttpServletResponse call(String path, String refreshToken) throws Exception {
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refreshToken + "\"}")).andReturn().getResponse();
	}

	private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			CountDownLatch ready = new CountDownLatch(tasks.size());
			CountDownLatch go = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return task.call();
				}));
			}
			ready.await();
			go.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> f : futures) {
				results.add(f.get());
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void sameRefreshTokenUsedConcurrentlyYieldsExactlyOneWinnerAndRevokesTheFamily() throws Exception {
		Tokens tokens = AuthTestClient.guest(mvc, "동시갱신");
		List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			tasks.add(() -> call("/api/auth/refresh", tokens.refreshToken()));
		}

		List<MockHttpServletResponse> responses = runTogether(tasks);

		List<MockHttpServletResponse> ok = responses.stream().filter(r -> r.getStatus() == 200).toList();
		assertThat(ok).as("정확히 한 요청만 새 토큰을 받는다").hasSize(1);
		assertThat(responses.stream().filter(r -> r.getStatus() == 401)).hasSize(5);
		// 나머지 요청은 이미 폐기된 토큰의 재사용으로 감지되어 family 전체가 폐기된다 (엄격한 정책)
		String winnersToken = JsonPath.read(ok.get(0).getContentAsString(), "$.refreshToken");
		assertThat(call("/api/auth/refresh", winnersToken).getStatus()).isEqualTo(401);
	}

	@Test
	void logoutRacingWithRefreshNeverLeavesAUsableToken() throws Exception {
		for (int round = 0; round < 15; round++) {
			Tokens tokens = AuthTestClient.guest(mvc, "경쟁" + round);
			List<Callable<MockHttpServletResponse>> tasks = List.of(
					() -> call("/api/auth/refresh", tokens.refreshToken()),
					() -> call("/api/auth/logout", tokens.refreshToken()));

			List<MockHttpServletResponse> responses = runTogether(tasks);

			assertThat(call("/api/auth/refresh", tokens.refreshToken()).getStatus()).isEqualTo(401);
			MockHttpServletResponse refresh = responses.get(0);
			if (refresh.getStatus() == 200) {
				String newToken = JsonPath.read(refresh.getContentAsString(), "$.refreshToken");
				assertThat(call("/api/auth/refresh", newToken).getStatus())
						.as("round %d: 로그아웃과 경쟁해 받은 새 토큰도 살아남으면 안 된다", round).isEqualTo(401);
			}
			assertThat(responses.get(1).getStatus()).isEqualTo(204);
		}
	}
}
