package crossword2.puzzle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import crossword2.grid.Direction;
import crossword2.word.Difficulty;
import crossword2.word.Word;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "puzzles")
public class Puzzle {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private int size;

	@Column(nullable = false)
	private long seed;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Difficulty difficulty;

	/** null이면 전체 주제. */
	@Column(length = 50)
	private String topic;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PuzzleType type;

	@Column(nullable = false)
	private int wordCount;

	@Column(nullable = false)
	private Instant createdAt;

	@OneToMany(mappedBy = "puzzle", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("number ASC, direction ASC")
	private List<PuzzleEntry> entries = new ArrayList<>();

	protected Puzzle() {
	}

	public Puzzle(int size, long seed, Difficulty difficulty, String topic, PuzzleType type, int wordCount,
			Instant createdAt) {
		this.size = size;
		this.seed = seed;
		this.difficulty = difficulty;
		this.topic = topic;
		this.type = type;
		this.wordCount = wordCount;
		this.createdAt = createdAt;
	}

	public PuzzleEntry addEntry(Word word, int startRow, int startCol, Direction direction, int number) {
		PuzzleEntry entry = new PuzzleEntry(this, word, startRow, startCol, direction, number);
		entries.add(entry);
		return entry;
	}

	public Long getId() {
		return id;
	}

	public int getSize() {
		return size;
	}

	public long getSeed() {
		return seed;
	}

	public Difficulty getDifficulty() {
		return difficulty;
	}

	public String getTopic() {
		return topic;
	}

	public PuzzleType getType() {
		return type;
	}

	public int getWordCount() {
		return wordCount;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public List<PuzzleEntry> getEntries() {
		return entries;
	}
}
