package crossword2.puzzle;

import crossword2.grid.Direction;
import crossword2.word.Word;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "puzzle_entries")
public class PuzzleEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "puzzle_id")
	private Puzzle puzzle;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "word_id")
	private Word word;

	@Column(nullable = false)
	private int startRow;

	@Column(nullable = false)
	private int startCol;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Direction direction;

	@Column(nullable = false, name = "entry_number")
	private int number;

	protected PuzzleEntry() {
	}

	PuzzleEntry(Puzzle puzzle, Word word, int startRow, int startCol, Direction direction, int number) {
		this.puzzle = puzzle;
		this.word = word;
		this.startRow = startRow;
		this.startCol = startCol;
		this.direction = direction;
		this.number = number;
	}

	public Long getId() {
		return id;
	}

	public Puzzle getPuzzle() {
		return puzzle;
	}

	public Word getWord() {
		return word;
	}

	public int getStartRow() {
		return startRow;
	}

	public int getStartCol() {
		return startCol;
	}

	public Direction getDirection() {
		return direction;
	}

	public int getNumber() {
		return number;
	}
}
