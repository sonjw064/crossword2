package crossword2.room;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import crossword2.auth.Owner;
import crossword2.progress.PlayRecordRepository;
import crossword2.puzzle.PlayStatus;
import crossword2.puzzle.Puzzle;
import crossword2.puzzle.PuzzleRepository;
import crossword2.puzzle.PuzzleType;

/** 방 설정에 맞는 미리 생성된 퍼즐을 고른다. 참가자가 이미 푼 퍼즐은 가능하면 피한다. */
@Component
public class RoomPuzzleSelector {

	public record Selection(long puzzleId, boolean someoneKnowsIt) {
	}

	private final PuzzleRepository puzzles;
	private final PlayRecordRepository records;

	public RoomPuzzleSelector(PuzzleRepository puzzles, PlayRecordRepository records) {
		this.puzzles = puzzles;
		this.records = records;
	}

	private static Specification<Puzzle> matching(RoomSettings s) {
		Specification<Puzzle> spec = (root, q, cb) -> cb.and(cb.equal(root.get("type"), PuzzleType.NORMAL),
				cb.equal(root.get("difficulty"), s.difficulty()), cb.equal(root.get("size"), s.size()));
		if (s.topic() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("topic"), s.topic()));
		}
		return spec;
	}

	@Transactional(readOnly = true)
	public long countMatching(RoomSettings settings) {
		return puzzles.count(matching(settings));
	}

	/**
	 * 조건에 맞는 퍼즐 중 참가자 누구도 완료한 적 없는 것을 무작위로 고른다. 모두 푼 적이 있다면 그 중에서 고르고 알려준다.
	 *
	 * @return 조건에 맞는 퍼즐이 없으면 null
	 */
	@Transactional(readOnly = true)
	public Selection pick(RoomSettings settings, List<Owner> participants) {
		List<Long> ids = puzzles.findAll(matching(settings)).stream().map(Puzzle::getId).toList();
		if (ids.isEmpty()) {
			return null;
		}
		Set<Long> known = new HashSet<>();
		for (Owner owner : participants) {
			known.addAll(records.distinctPuzzleIds(owner.type(), owner.id(), PlayStatus.COMPLETED));
		}
		List<Long> fresh = ids.stream().filter(id -> !known.contains(id)).toList();
		List<Long> pool = fresh.isEmpty() ? ids : fresh;
		long chosen = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
		return new Selection(chosen, fresh.isEmpty());
	}
}
