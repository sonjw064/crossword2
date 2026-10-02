package crossword2.puzzle;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import crossword2.word.Difficulty;

public interface PuzzleRepository extends JpaRepository<Puzzle, Long>, JpaSpecificationExecutor<Puzzle> {

	// 난이도는 문자열로 저장되어 DB 정렬이 쉬움/보통/어려움 순이 아니므로 서비스에서 enum 순서로 정렬한다
	@Query("select distinct p.difficulty from Puzzle p")
	List<Difficulty> findDistinctDifficulties();

	@Query("select distinct p.topic from Puzzle p where p.topic is not null order by p.topic")
	List<String> findDistinctTopics();

	@Query("select distinct p.size from Puzzle p order by p.size")
	List<Integer> findDistinctSizes();

	@EntityGraph(attributePaths = { "entries", "entries.word" })
	Optional<Puzzle> findWithEntriesById(Long id);
}
