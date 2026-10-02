package crossword2.word;

import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "words")
public class Word {

	public static final int MIN_LENGTH = 2;
	public static final int MAX_LENGTH = 15;
	private static final Pattern ENGLISH = Pattern.compile("[a-z]{" + MIN_LENGTH + "," + MAX_LENGTH + "}");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = MAX_LENGTH)
	private String english;

	@Column(nullable = false, length = 100)
	private String korean;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private PartOfSpeech partOfSpeech;

	@Column(length = 300)
	private String definition;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Difficulty difficulty;

	@Column(nullable = false, length = 50)
	private String topic;

	@Column(length = 300)
	private String example;

	@Column(nullable = false)
	private boolean active = true;

	protected Word() {
	}

	public Word(String english, String korean, PartOfSpeech partOfSpeech, String definition,
			Difficulty difficulty, String topic, String example) {
		this.english = normalizeEnglish(english);
		this.korean = requireText(korean, "korean");
		this.partOfSpeech = partOfSpeech;
		this.definition = definition;
		this.difficulty = requireNonNull(difficulty, "difficulty");
		this.topic = requireText(topic, "topic");
		this.example = example;
	}

	/** 앞뒤 공백을 제거하고 소문자로 바꾼 뒤, 알파벳 {@value MIN_LENGTH}~{@value MAX_LENGTH}자인지 검증한다. */
	static String normalizeEnglish(String raw) {
		String value = requireText(raw, "english").toLowerCase(Locale.ROOT);
		if (!ENGLISH.matcher(value).matches()) {
			throw new IllegalArgumentException(
					"english must be " + MIN_LENGTH + "-" + MAX_LENGTH + " letters a-z: " + raw);
		}
		return value;
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}

	private static <T> T requireNonNull(T value, String field) {
		if (value == null) {
			throw new IllegalArgumentException(field + " must not be null");
		}
		return value;
	}

	public Long getId() {
		return id;
	}

	public String getEnglish() {
		return english;
	}

	public String getKorean() {
		return korean;
	}

	public PartOfSpeech getPartOfSpeech() {
		return partOfSpeech;
	}

	public String getDefinition() {
		return definition;
	}

	public Difficulty getDifficulty() {
		return difficulty;
	}

	public String getTopic() {
		return topic;
	}

	public String getExample() {
		return example;
	}

	public boolean isActive() {
		return active;
	}
}
