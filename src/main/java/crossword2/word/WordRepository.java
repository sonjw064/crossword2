package crossword2.word;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WordRepository extends JpaRepository<Word, Long> {

	boolean existsByEnglish(String english);

	List<Word> findByActiveTrueAndDifficultyAndTopic(Difficulty difficulty, String topic);
}
