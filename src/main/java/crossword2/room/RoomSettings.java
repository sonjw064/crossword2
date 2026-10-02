package crossword2.room;

import java.util.Set;

import org.springframework.http.HttpStatus;

import crossword2.common.ApiException;
import crossword2.word.Difficulty;

/**
 * 방 설정. 방장이 대기 중에 정한다. {@code topic}이 null이면 모든 주제.
 *
 * @param timeLimitSec 경기 제한 시간(초)
 */
public record RoomSettings(Difficulty difficulty, String topic, int size, int timeLimitSec, int maxPlayers, RoomMode mode) {

	public static final int MIN_PLAYERS = 2;
	public static final int MAX_PLAYERS = 8;
	public static final int MIN_SIZE = 7;
	public static final int MAX_SIZE = 15;
	public static final int MIN_TIME_LIMIT_SEC = 60;
	public static final int MAX_TIME_LIMIT_SEC = 3600;
	private static final int MAX_TOPIC_LENGTH = 50;

	/** 값을 검증해 정규화한 설정을 만든다(주제는 공백을 정리하고 비면 null). 잘못되면 400 SETTINGS_INVALID. */
	public static RoomSettings validated(Difficulty difficulty, String topic, int size, int timeLimitSec, int maxPlayers,
			RoomMode mode, Set<RoomMode> enabledModes) {
		if (difficulty == null || mode == null) {
			throw invalid("difficulty and mode are required");
		}
		if (!enabledModes.contains(mode)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "MODE_NOT_AVAILABLE", "this mode is not available yet: " + mode);
		}
		if (size < MIN_SIZE || size > MAX_SIZE) {
			throw invalid("size must be between " + MIN_SIZE + " and " + MAX_SIZE);
		}
		if (timeLimitSec < MIN_TIME_LIMIT_SEC || timeLimitSec > MAX_TIME_LIMIT_SEC) {
			throw invalid("timeLimitSec must be between " + MIN_TIME_LIMIT_SEC + " and " + MAX_TIME_LIMIT_SEC);
		}
		if (maxPlayers < MIN_PLAYERS || maxPlayers > MAX_PLAYERS) {
			throw invalid("maxPlayers must be between " + MIN_PLAYERS + " and " + MAX_PLAYERS);
		}
		String cleanTopic = topic == null || topic.isBlank() ? null : topic.strip();
		if (cleanTopic != null && cleanTopic.length() > MAX_TOPIC_LENGTH) {
			throw invalid("topic is too long");
		}
		return new RoomSettings(difficulty, cleanTopic, size, timeLimitSec, maxPlayers, mode);
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID", message);
	}
}
