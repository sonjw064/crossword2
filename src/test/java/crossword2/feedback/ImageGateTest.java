package crossword2.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import crossword2.common.ApiException;

class ImageGateTest {

	private static ImageGate gate(int permits, Duration wait) {
		return new ImageGate(new FeedbackProperties("unused", 2_097_152, 4096, 8_500_000, Duration.ofDays(90), 5, 10,
				permits, wait, 200));
	}

	private static void assertBusy(Throwable thrown) {
		assertThat(thrown).isInstanceOfSatisfying(ApiException.class, e -> {
			assertThat(e.status().value()).isEqualTo(503);
			assertThat(e.code()).isEqualTo("SERVER_BUSY");
		});
	}

	@Test
	void runsTheWorkAndReturnsItsResult() {
		assertThat(gate(1, Duration.ofMillis(50)).run(() -> "done")).isEqualTo("done");
	}

	@Test
	void rejectsWithBusyWhenAllSlotsAreTakenAndFreesThemAfterwards() throws Exception {
		ImageGate gate = gate(1, Duration.ofMillis(100));
		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<String> first = pool.submit(() -> gate.run(() -> {
				holding.countDown();
				await(release);
				return "first";
			}));
			holding.await();

			assertThatThrownBy(() -> gate.run(() -> "second")).satisfies(ImageGateTest::assertBusy);

			release.countDown();
			assertThat(first.get()).isEqualTo("first");
			assertThat(gate.run(() -> "third")).as("끝나면 자리가 다시 난다").isEqualTo("third");
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aSlotIsFreedEvenWhenTheWorkFails() {
		ImageGate gate = gate(1, Duration.ofMillis(50));

		assertThatThrownBy(() -> gate.run(() -> {
			throw new IllegalStateException("boom");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(gate.run(() -> "ok")).isEqualTo("ok");
	}

	@Test
	void neverRunsMoreImagesAtOnceThanThePermitCount() throws Exception {
		ImageGate gate = gate(2, Duration.ofSeconds(10));
		AtomicInteger active = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				futures.add(pool.submit(() -> gate.run(() -> {
					int now = active.incrementAndGet();
					peak.accumulateAndGet(now, Math::max);
					sleep(40);
					active.decrementAndGet();
					return 1;
				})));
			}
			for (Future<Integer> f : futures) {
				assertThat(f.get()).isEqualTo(1);
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(peak.get()).isLessThanOrEqualTo(2).isGreaterThanOrEqualTo(1);
	}

	/** 최대 허용 크기(4K UHD) 이미지를 여러 요청이 동시에 올려도 처리 동시 수는 한도를 넘지 않고, 처리된 결과는 모두 정상이다. */
	@Test
	void manyMaximumSizeImagesAreProcessedWithinTheConcurrencyBudget() throws Exception {
		byte[] uhd = pngOfSize(3840, 2160);
		assertThat(uhd.length).as("테스트 입력은 업로드 한도(2MB) 안이어야 한다").isLessThan(2 * 1024 * 1024);
		ImageGate gate = gate(2, Duration.ofSeconds(60));
		AtomicInteger active = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(6);
		try {
			List<Future<byte[]>> futures = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				futures.add(pool.submit(() -> gate.run(() -> {
					peak.accumulateAndGet(active.incrementAndGet(), Math::max);
					try {
						return ImageSanitizer.sanitize(uhd, 4096, 8_500_000).bytes();
					} finally {
						active.decrementAndGet();
					}
				})));
			}
			for (Future<byte[]> f : futures) {
				assertThat(f.get()).isNotEmpty();
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(peak.get()).isLessThanOrEqualTo(2);
	}

	private static byte[] pngOfSize(int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y += 40) {
			for (int x = 0; x < width; x += 40) {
				int color = ((x / 40 + y / 40) % 2 == 0) ? 0x3b5bdb : 0xffffff;
				for (int dy = 0; dy < 40 && y + dy < height; dy++) {
					for (int dx = 0; dx < 40 && x + dx < width; dx++) {
						image.setRGB(x + dx, y + dy, color);
					}
				}
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
