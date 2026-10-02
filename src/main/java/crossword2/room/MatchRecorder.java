package crossword2.room;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crossword2.auth.AccountLock;
import crossword2.auth.Owner;

/**
 * 끝난 경기를 DB에 남긴다. 참가자의 계정 행을 (정해진 순서로) 먼저 잠근 뒤 쓰므로 게스트 이전과 경쟁해도 주인 없는 기록이 생기지 않는다:
 * 그 사이 회원으로 이전된 게스트의 기록은 회원 소유로 남는다.
 */
@Service
public class MatchRecorder {

	private final MatchResultRepository results;
	private final MatchParticipantRepository participants;
	private final AccountLock accountLock;

	public MatchRecorder(MatchResultRepository results, MatchParticipantRepository participants, AccountLock accountLock) {
		this.results = results;
		this.participants = participants;
		this.accountLock = accountLock;
	}

	@Transactional
	public Long record(MatchOutcome outcome) {
		Map<Owner, Owner> current = new HashMap<>();
		outcome.standings().stream().map(Match.Standing::owner)
				.sorted(Comparator.comparing((Owner o) -> o.type().name()).thenComparing(Owner::id))
				.forEach(owner -> current.put(owner, accountLock.lockCurrent(owner)));

		MatchResult result = results.save(new MatchResult(outcome.puzzleId(), outcome.mode(), outcome.startedAt(),
				outcome.endedAt(), outcome.standings().size()));
		List<MatchParticipant> rows = outcome.standings().stream()
				.map(s -> new MatchParticipant(result.getId(), current.get(s.owner()), s.nickname(), s.score(), s.solved(),
						s.rank(), s.finishedAt(), s.abandoned()))
				.toList();
		participants.saveAll(rows);
		return result.getId();
	}
}
