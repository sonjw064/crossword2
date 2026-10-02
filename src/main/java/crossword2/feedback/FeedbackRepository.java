package crossword2.feedback;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crossword2.auth.OwnerType;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

	List<Feedback> findByAuthorTypeAndAuthorIdOrderByCreatedAtDescIdDesc(OwnerType authorType, String authorId);

	/** 익명화 대상 ID(작은 묶음 단위): 아직 작성자가 남아 있는 게스트 문의 중 기준 시각 이전에 쓴 것. */
	@Query("select f.id from Feedback f where f.authorType = :type and f.authorId is not null and f.createdAt < :cutoff order by f.id")
	List<Long> findStaleGuestFeedbackIds(@Param("type") OwnerType type, @Param("cutoff") Instant cutoff, Pageable pageable);

	/**
	 * 조회해 둔 객체를 고쳐 쓰지 않고, 실행 시점에도 여전히 게스트 소유이고 기간이 지난 행만 익명화한다.
	 * 그 사이 회원으로 이전된 문의는 조건에 맞지 않아 건너뛴다(이전과의 경쟁에서 회원 문의를 지우지 않는다).
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Feedback f set f.authorId = null, f.deviceInfo = null, f.anonymizedAt = :now where f.id in :ids and f.authorType = :type and f.authorId is not null and f.createdAt < :cutoff")
	int anonymizeStillStale(@Param("ids") Collection<Long> ids, @Param("type") OwnerType type,
			@Param("cutoff") Instant cutoff, @Param("now") Instant now);

	/** 대상 묶음 중 지금 익명화된 상태인 문의(타임스탬프 정밀도 차이로 시각을 비교하지 않는다). */
	@Query("select f.id from Feedback f where f.id in :ids and f.authorId is null and f.anonymizedAt is not null")
	List<Long> findAnonymizedIds(@Param("ids") Collection<Long> ids);

	/** 게스트 → 회원 이전. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Feedback f set f.authorType = :toType, f.authorId = :toId where f.authorType = :fromType and f.authorId = :fromId")
	int reassignAuthor(@Param("fromType") OwnerType fromType, @Param("fromId") String fromId,
			@Param("toType") OwnerType toType, @Param("toId") String toId);
}
