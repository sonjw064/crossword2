package crossword2.puzzle;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PuzzleRepository extends JpaRepository<Puzzle, Long>, JpaSpecificationExecutor<Puzzle> {

	@EntityGraph(attributePaths = { "entries", "entries.word" })
	Optional<Puzzle> findWithEntriesById(Long id);
}
