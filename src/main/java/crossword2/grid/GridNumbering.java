package crossword2.grid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 좌상단부터 행 우선으로 훑으며 번호를 부여한다. 같은 칸에서 가로와 세로가 시작하면 번호를 공유한다. */
final class GridNumbering {

	private GridNumbering() {
	}

	/** 번호를 부여해 (번호, 가로 먼저) 순으로 정렬한 새 목록을 돌려준다. */
	static List<PlacedWord> assign(List<PlacedWord> words) {
		Map<Long, Integer> numberByStart = new HashMap<>();
		List<PlacedWord> sortedByStart = new ArrayList<>(words);
		sortedByStart.sort(Comparator.comparingInt(PlacedWord::row).thenComparingInt(PlacedWord::col));
		int next = 1;
		for (PlacedWord w : sortedByStart) {
			long key = key(w.row(), w.col());
			if (!numberByStart.containsKey(key)) {
				numberByStart.put(key, next++);
			}
		}
		return words.stream()
				.map(w -> w.withNumber(numberByStart.get(key(w.row(), w.col()))))
				.sorted(Comparator.comparingInt(PlacedWord::number).thenComparing(PlacedWord::direction))
				.toList();
	}

	private static long key(int row, int col) {
		return ((long) row << 32) | col;
	}
}
