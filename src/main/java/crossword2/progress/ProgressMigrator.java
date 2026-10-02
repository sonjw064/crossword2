package crossword2.progress;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import crossword2.auth.GuestDataMigrator;
import crossword2.auth.Owner;

/** 게스트 → 회원 이전: 풀이 기록은 소유자를 바꾸고, 오답노트는 같은 단어끼리 병합한다. */
@Component
public class ProgressMigrator implements GuestDataMigrator {

	private final PlayRecordRepository records;
	private final WrongAnswerRepository wrongAnswers;

	public ProgressMigrator(PlayRecordRepository records, WrongAnswerRepository wrongAnswers) {
		this.records = records;
		this.wrongAnswers = wrongAnswers;
	}

	@Override
	@Transactional
	public void migrate(Owner guest, Owner member) {
		records.reassignOwner(guest.type(), guest.id(), member.type(), member.id());

		Map<Long, WrongAnswer> mine = new HashMap<>();
		wrongAnswers.findByOwnerTypeAndOwnerId(member.type(), member.id()).forEach(w -> mine.put(w.getWordId(), w));
		for (WrongAnswer fromGuest : wrongAnswers.findByOwnerTypeAndOwnerId(guest.type(), guest.id())) {
			WrongAnswer existing = mine.get(fromGuest.getWordId());
			if (existing != null) {
				existing.mergeFrom(fromGuest); // 횟수 합산, 최근 시각
				wrongAnswers.delete(fromGuest);
			} else {
				fromGuest.reassignTo(member);
				mine.put(fromGuest.getWordId(), fromGuest);
			}
		}
		wrongAnswers.flush();
	}
}
