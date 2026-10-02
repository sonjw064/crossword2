package crossword2.feedback;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

/** 첨부 테스트용 이미지 바이트를 만든다. */
final class TestImages {

	private TestImages() {
	}

	private static BufferedImage picture(int width, int height, int type) {
		BufferedImage image = new BufferedImage(width, height, type);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(Color.BLUE);
			g.fillRect(0, 0, width, height);
			g.setColor(Color.RED);
			g.fillRect(width / 4, height / 4, Math.max(1, width / 2), Math.max(1, height / 2));
		} finally {
			g.dispose();
		}
		return image;
	}

	static byte[] png(int width, int height) {
		return encode(picture(width, height, BufferedImage.TYPE_INT_ARGB), "png");
	}

	static byte[] jpeg(int width, int height) {
		return encode(picture(width, height, BufferedImage.TYPE_INT_RGB), "jpg");
	}

	static byte[] gif(int width, int height) {
		return encode(picture(width, height, BufferedImage.TYPE_INT_RGB), "gif");
	}

	static byte[] bmp(int width, int height) {
		return encode(picture(width, height, BufferedImage.TYPE_INT_RGB), "bmp");
	}

	private static byte[] encode(BufferedImage image, String format) {
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			if (!ImageIO.write(image, format, out)) {
				throw new IllegalStateException("no writer for " + format);
			}
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 정상 JPEG의 SOI 바로 뒤에 EXIF(APP1) 구간을 끼워 넣는다. */
	static byte[] jpegWithExif(int width, int height, String secret) {
		byte[] jpeg = jpeg(width, height);
		byte[] payload = ("Exif\0\0" + secret).getBytes(StandardCharsets.ISO_8859_1);
		ByteBuffer out = ByteBuffer.allocate(jpeg.length + payload.length + 4);
		out.put(jpeg, 0, 2); // SOI
		out.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (payload.length + 2)).put(payload);
		out.put(jpeg, 2, jpeg.length - 2);
		return out.array();
	}

	/** 정상 이미지 뒤에 임의 데이터를 덧붙인다(폴리글랏 시도). */
	static byte[] withTrailer(byte[] image, String trailer) {
		byte[] extra = trailer.getBytes(StandardCharsets.UTF_8);
		byte[] out = new byte[image.length + extra.length];
		System.arraycopy(image, 0, out, 0, image.length);
		System.arraycopy(extra, 0, out, image.length, extra.length);
		return out;
	}

	static byte[] truncated(byte[] image, int keep) {
		byte[] out = new byte[keep];
		System.arraycopy(image, 0, out, 0, keep);
		return out;
	}

	/** 헤더만 거대한 크기를 주장하는 PNG (실제 픽셀 데이터는 없다). 압축 폭탄 시도. */
	static byte[] pngClaiming(int width, int height) {
		ByteBuffer ihdr = ByteBuffer.allocate(13);
		ihdr.putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A });
		chunk(out, "IHDR", ihdr.array());
		chunk(out, "IDAT", new byte[] { 0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01 });
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
		out.writeBytes(typeBytes);
		out.writeBytes(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
	}

	static boolean contains(byte[] haystack, String needle) {
		return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
	}
}
