package crossword2.feedback;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.http.HttpStatus;

import crossword2.common.ApiException;

/**
 * 업로드된 스크린샷 검증과 재인코딩. 확장자나 Content-Type이 아니라 파일 앞부분(magic bytes)으로 PNG/JPEG만 받고,
 * 디코딩 전에 크기를 확인해 압축 폭탄을 막으며, 디코딩한 픽셀만으로 새로 인코딩해서 EXIF(위치 정보 등)와
 * 이미지 뒤에 덧붙인 임의 데이터(HTML/스크립트 폴리글랏)를 모두 제거한다.
 */
final class ImageSanitizer {

	record Sanitized(byte[] bytes, String contentType, String extension) {
	}

	private static final byte[] PNG_MAGIC = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
	private static final float JPEG_QUALITY = 0.9f;

	private ImageSanitizer() {
	}

	static Sanitized sanitize(byte[] data, int maxDimension, long maxPixels) {
		String format = detect(data);
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
			if (in == null || !readers.hasNext()) {
				throw invalid("the image could not be read");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true); // 메타데이터는 읽지 않는다
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width <= 0 || height <= 0 || width > maxDimension || height > maxDimension
						|| (long) width * height > maxPixels) {
					throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
							"the image is too large (max " + maxDimension + "px per side)");
				}
				BufferedImage image = reader.read(0);
				return "png".equals(format) ? encodePng(image) : encodeJpeg(image);
			} finally {
				reader.dispose();
			}
		} catch (ApiException e) {
			throw e;
		} catch (IOException | RuntimeException e) {
			throw invalid("the image is corrupted or unsupported");
		}
	}

	/** magic bytes로 형식을 판별한다. PNG/JPEG가 아니면 거부한다. */
	private static String detect(byte[] data) {
		if (data == null || data.length < 12) {
			throw invalid("the file is empty or too small");
		}
		boolean png = true;
		for (int i = 0; i < PNG_MAGIC.length; i++) {
			if (data[i] != PNG_MAGIC[i]) {
				png = false;
				break;
			}
		}
		if (png) {
			return "png";
		}
		if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
			return "jpeg";
		}
		throw invalid("only PNG and JPEG images are allowed");
	}

	private static Sanitized encodePng(BufferedImage image) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", out)) {
			throw invalid("the image could not be encoded");
		}
		return new Sanitized(out.toByteArray(), "image/png", "png");
	}

	private static Sanitized encodeJpeg(BufferedImage image) throws IOException {
		// JPEG는 투명도가 없으므로 흰 배경의 RGB로 그린 뒤 인코딩한다
		BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = rgb.createGraphics();
		try {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
			g.drawImage(image, 0, 0, null);
		} finally {
			g.dispose();
		}
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
				ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(JPEG_QUALITY);
			writer.setOutput(stream);
			writer.write(null, new IIOImage(rgb, null, null), param);
			stream.flush();
			return new Sanitized(out.toByteArray(), "image/jpeg", "jpg");
		} finally {
			writer.dispose();
		}
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT", message);
	}
}
