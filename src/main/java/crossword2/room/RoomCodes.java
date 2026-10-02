package crossword2.room;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * 방 코드: 6자리, 혼동되는 글자(0/O, 1/I)를 뺀 32글자(숫자 2~9, 알파벳 I·O 제외). 경우의 수가 약 10억이라 추측하기 어렵지만
 * 입장 시도 자체도 Rate limit으로 제한한다.
 */
public final class RoomCodes {

	public static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
	public static final int LENGTH = 6;
	private static final Pattern FORMAT = Pattern.compile("^[" + ALPHABET + "]{" + LENGTH + "}$");
	private static final Random SECURE = new SecureRandom();

	private RoomCodes() {
	}

	public static String generate() {
		return generate(SECURE);
	}

	static String generate(Random random) {
		StringBuilder code = new StringBuilder(LENGTH);
		for (int i = 0; i < LENGTH; i++) {
			code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return code.toString();
	}

	/** 사용자가 입력한 코드를 정규화한다(공백 제거, 대문자). 형식에 맞지 않으면 null. */
	public static String normalize(String raw) {
		if (raw == null) {
			return null;
		}
		String code = raw.strip().toUpperCase(Locale.ROOT);
		return FORMAT.matcher(code).matches() ? code : null;
	}
}
