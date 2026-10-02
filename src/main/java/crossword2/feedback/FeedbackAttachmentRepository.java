package crossword2.feedback;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackAttachmentRepository extends JpaRepository<FeedbackAttachment, Long> {

	Optional<FeedbackAttachment> findByFeedbackId(Long feedbackId);

	List<FeedbackAttachment> findByFeedbackIdIn(Collection<Long> feedbackIds);
}
