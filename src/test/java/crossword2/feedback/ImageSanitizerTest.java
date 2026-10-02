package crossword2.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import crossword2.common.ApiException;
import crossword2.feedback.ImageSanitizer.Sanitized;

class ImageSanitizerTest {

	private static final int MAX_DIM = 4096;
	private static final long MAX_PIXELS = 12_000_000;

	private static Sanitized sanitize(byte[] data) {
		return ImageSanitizer.sanitize(data, MAX_DIM, MAX_PIXELS);
	}

	private static void assertRejected(byte[] data) {
		assertThatThrownBy(() -> sanitize(data)).isInstanceOfSatisfying(ApiException.class, e -> {
			assertThat(e.status().value()).isEqualTo(400);
			assertThat(e.code()).isEqualTo("INVALID_ATTACHMENT");
		});
	}

	private static BufferedImage decode(byte[] bytes) throws Exception {
		return ImageIO.read(new ByteArrayInputStream(bytes));
	}

	// ---- 허용 ----

	@Test
	void acceptsPngAndKeepsItsSize() throws Exception {
		Sanitized result = sanitize(TestImages.png(40, 30));

		assertThat(result.contentType()).isEqualTo("image/png");
		assertThat(result.extension()).isEqualTo("png");
		BufferedImage image = decode(result.bytes());
		assertThat(image.getWidth()).isEqualTo(40);
		assertThat(image.getHeight()).isEqualTo(30);
	}

	@Test
	void acceptsJpegAndKeepsItsSize() throws Exception {
		Sanitized result = sanitize(TestImages.jpeg(64, 48));

		assertThat(result.contentType()).isEqualTo("image/jpeg");
		assertThat(result.extension()).isEqualTo("jpg");
		BufferedImage image = decode(result.bytes());
		assertThat(image.getWidth()).isEqualTo(64);
		assertThat(image.getHeight()).isEqualTo(48);
	}

	@Test
	void theLargestAllowedImageIsAccepted() {
		assertThat(sanitize(TestImages.png(3000, 3000)).bytes()).isNotEmpty();
	}

	// ---- 정화: 메타데이터와 덧붙은 데이터 제거 ----

	@Test
	void exifMetadataIsRemoved() {
		byte[] withExif = TestImages.jpegWithExif(32, 32, "SECRET-GPS-LOCATION");
		assertThat(TestImages.contains(withExif, "SECRET-GPS-LOCATION")).as("테스트 입력에는 EXIF가 있어야 한다").isTrue();

		Sanitized result = sanitize(withExif);

		assertThat(TestImages.contains(result.bytes(), "SECRET-GPS-LOCATION")).isFalse();
	}

	@Test
	void dataAppendedAfterAPngIsDropped() throws Exception {
		byte[] polyglot = TestImages.withTrailer(TestImages.png(20, 20), "<script>alert('xss')</script>");

		Sanitized result = sanitize(polyglot);

		assertThat(TestImages.contains(result.bytes(), "<script>")).isFalse();
		assertThat(decode(result.bytes()).getWidth()).isEqualTo(20);
	}

	@Test
	void dataAppendedAfterAJpegIsDropped() {
		byte[] polyglot = TestImages.withTrailer(TestImages.jpeg(20, 20), "<html><script>steal()</script></html>");

		Sanitized result = sanitize(polyglot);

		assertThat(TestImages.contains(result.bytes(), "<script>")).isFalse();
	}

	@Test
	void theOutputIsAlwaysAFreshEncodingNotTheUploadedBytes() {
		byte[] upload = TestImages.png(30, 30);

		assertThat(sanitize(upload).bytes()).isNotSameAs(upload);
	}

	// ---- 거부: 형식 ----

	@Test
	void rejectsOtherImageFormats() {
		assertRejected(TestImages.gif(10, 10));
		assertRejected(TestImages.bmp(10, 10));
	}

	@Test
	void rejectsNonImagesEvenIfTheyClaimToBePng() {
		assertRejected("this is just text pretending to be a png image".getBytes(StandardCharsets.UTF_8));
		assertRejected("<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8));
		assertRejected("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
		assertRejected("%PDF-1.7 fake pdf content here".getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void rejectsEmptyAndTinyInput() {
		assertRejected(new byte[0]);
		assertRejected(new byte[] { (byte) 0x89, 'P', 'N', 'G' });
	}

	@Test
	void rejectsTruncatedImages() {
		assertRejected(TestImages.truncated(TestImages.png(200, 200), 60));
		assertRejected(TestImages.truncated(TestImages.jpeg(200, 200), 120));
	}

	@Test
	void rejectsAPngWithGarbageAfterTheMagicBytes() {
		byte[] fake = new byte[200];
		System.arraycopy(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A }, 0, fake, 0, 8);

		assertRejected(fake);
	}

	// ---- 거부: 크기 (압축 폭탄) ----

	@Test
	void rejectsHugeDimensionsBeforeDecodingThem() {
		assertRejected(TestImages.pngClaiming(20_000, 20_000));
		assertRejected(TestImages.pngClaiming(100_000, 10));
	}

	@Test
	void rejectsTooManyPixels() {
		// 각 변은 4096 이하이지만 전체 픽셀이 한도를 넘는다
		assertThatThrownBy(() -> ImageSanitizer.sanitize(TestImages.pngClaiming(4000, 4000), MAX_DIM, MAX_PIXELS))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void rejectsADimensionOverTheLimit() {
		assertRejected(TestImages.pngClaiming(MAX_DIM + 1, 10));
	}
}
