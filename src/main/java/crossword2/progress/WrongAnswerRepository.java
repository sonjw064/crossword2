package crossword2.progress;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import crossword2.auth.OwnerType;

public interface WrongAnswerRepository extends JpaRepository<WrongAnswer, Long> {

	List<WrongAnswer> findByOwnerTypeAndOwnerId(OwnerType ownerType, String ownerId);

	List<WrongAnswer> findByOwnerTypeAndOwnerIdAndWordIdIn(OwnerType ownerType, String ownerId, Collection<Long> wordIds);

	Page<WrongAnswer> findByOwnerTypeAndOwnerId(OwnerType ownerType, String ownerId, Pageable pageable);
}
