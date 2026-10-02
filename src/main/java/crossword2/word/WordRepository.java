package crossword2.word;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WordRepository extends JpaRepository<Word, Long> {

	boolean existsByEnglish(String english);

	List<Word> findByActiveTrueAndDifficultyAndTopic(Difficulty difficulty, String topic);

	List<Word> findByActiveTrueAndDifficultyInOrderById(Collection<Difficulty> difficulties);

	List<Word> findByActiveTrueAndDifficultyInAndTopicOrderById(Collection<Difficulty> difficulties, String topic);

	@Query("select distinct w.topic from Word w where w.active = true order by w.topic")
	List<String> findActiveTopics();
}
