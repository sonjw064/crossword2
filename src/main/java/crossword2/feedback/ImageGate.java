package crossword2.feedback;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import crossword2.common.ApiException;

/**
 * 이미지 디코딩/재인코딩의 동시 실행 수를 제한한다. 최대 해상도 이미지는 요청 하나가 수십 MB의 픽셀 버퍼를 쓰므로,
 * 여러 요청이 동시에 처리되면 힙이 고갈될 수 있다. 자리가 나지 않으면 잠시 기다린 뒤 503(SERVER_BUSY)으로 거절한다.
 */
@Component
public class ImageGate {

	private final Semaphore permits;
	private final long waitMillis;

	public ImageGate(FeedbackProperties props) {
		this.permits = new Semaphore(Math.max(1, props.maxConcurrentImageProcessing()));
		this.waitMillis = props.imageProcessingWait().toMillis();
	}

	public <T> T run(Supplier<T> work) {
		boolean acquired;
		try {
			acquired = permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw busy();
		}
		if (!acquired) {
			throw busy();
		}
		try {
			return work.get();
		} finally {
			permits.release();
		}
	}

	private static ApiException busy() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVER_BUSY",
				"the server is busy processing images, please try again shortly");
	}
}
