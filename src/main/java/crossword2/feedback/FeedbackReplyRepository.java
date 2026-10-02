package crossword2.feedback;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crossword2.auth.OwnerType;

public interface FeedbackReplyRepository extends JpaRepository<FeedbackReply, Long> {

	List<FeedbackReply> findByFeedbackIdInOrderByCreatedAtAscIdAsc(Collection<Long> feedbackIds);

	@Query("select count(r) from FeedbackReply r, Feedback f where r.feedbackId = f.id and f.authorType = :type and f.authorId = :id and r.readAt is null")
	long countUnread(@Param("type") OwnerType type, @Param("id") String id);

	/** 답변이 요청한 사용자의 문의에 달린 것일 때만 돌려준다(남의 답변과 없는 답변을 구분하지 않는다). */
	@Query("select r from FeedbackReply r, Feedback f where r.id = :replyId and r.feedbackId = f.id and f.authorType = :type and f.authorId = :id")
	Optional<FeedbackReply> findOwned(@Param("replyId") Long replyId, @Param("type") OwnerType type, @Param("id") String id);

	/** 읽은 시각은 처음 한 번만 기록한다(이미 읽었으면 0건 갱신). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update FeedbackReply r set r.readAt = :now where r.id = :id and r.readAt is null")
	int markReadIfUnread(@Param("id") Long id, @Param("now") Instant now);
}
