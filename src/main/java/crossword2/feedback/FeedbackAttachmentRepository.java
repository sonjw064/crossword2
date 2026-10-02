package crossword2.feedback;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackAttachmentRepository extends JpaRepository<FeedbackAttachment, Long> {

	Optional<FeedbackAttachment> findByFeedbackId(Long feedbackId);

	List<FeedbackAttachment> findByFeedbackIdIn(Collection<Long> feedbackIds);

	/** 삭제 대기 중인 첨부를 id 순서로(키셋 방식) 가져온다. */
	List<FeedbackAttachment> findByDeleteRequestedAtIsNotNullAndIdGreaterThanOrderByIdAsc(Long afterId, Pageable pageable);
}
