package crossword2.feedback;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import crossword2.auth.GuestDataMigrator;
import crossword2.auth.Owner;

/** 게스트가 회원이 되면 그 게스트의 문의를 회원 소유로 옮긴다. 이후 그 문의의 답변을 받을 수 있다. */
@Component
public class FeedbackMigrator implements GuestDataMigrator {

	private final FeedbackRepository feedbacks;

	public FeedbackMigrator(FeedbackRepository feedbacks) {
		this.feedbacks = feedbacks;
	}

	@Override
	@Transactional
	public void migrate(Owner guest, Owner member) {
		feedbacks.reassignAuthor(guest.type(), guest.id(), member.type(), member.id());
	}
}
