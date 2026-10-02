package crossword2.feedback;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crossword2.auth.OwnerType;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

	List<Feedback> findByAuthorTypeAndAuthorIdOrderByCreatedAtDescIdDesc(OwnerType authorType, String authorId);

	/** 익명화 대상: 아직 작성자가 남아 있는 게스트 문의 중 기준 시각 이전에 쓴 것. */
	List<Feedback> findByAuthorTypeAndAuthorIdIsNotNullAndCreatedAtBefore(OwnerType authorType, Instant cutoff);

	/** 게스트 → 회원 이전. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Feedback f set f.authorType = :toType, f.authorId = :toId where f.authorType = :fromType and f.authorId = :fromId")
	int reassignAuthor(@Param("fromType") OwnerType fromType, @Param("fromId") String fromId,
			@Param("toType") OwnerType toType, @Param("toId") String toId);
}
