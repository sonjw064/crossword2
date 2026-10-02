package crossword2.grid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 생성기와 독립적으로, 완성된 {@link GridLayout}이 5장의 규칙을 만족하는지 검사한다. */
public final class GridValidator {

	private GridValidator() {
	}

	/** @return 위반 사항 목록 (비어 있으면 유효) */
	public static List<String> validate(GridLayout layout) {
		List<String> violations = new ArrayList<>();
		int size = layout.size();
		List<PlacedWord> words = layout.words();

		if (layout.rows().size() != size || layout.rows().stream().anyMatch(r -> r.length() != size)) {
			violations.add("rows do not form a " + size + "x" + size + " grid");
			return violations;
		}
		if (words.isEmpty()) {
			violations.add("no words placed");
			return violations;
		}

		Set<String> texts = new HashSet<>();
		for (PlacedWord w : words) {
			if (!texts.add(w.word())) {
				violations.add("duplicate word: " + w.word());
			}
		}
		if (!checkWordsAgainstCells(layout, violations)) {
			return violations;
		}
		checkNoStrayRuns(layout, violations);
		checkCrossingAndConnectivity(layout, violations);
		checkNumbering(layout, violations);
		return violations;
	}

	public static boolean isValid(GridLayout layout) {
		return validate(layout).isEmpty();
	}

	/** 모든 단어가 범위 안에 있고 칸의 글자와 일치하는지. 범위를 벗어나면 이후 검사를 중단한다. */
	private static boolean checkWordsAgainstCells(GridLayout layout, List<String> violations) {
		int size = layout.size();
		boolean inBounds = true;
		for (PlacedWord w : layout.words()) {
			if (w.length() < 2) {
				violations.add("word shorter than 2 letters: " + w.word());
			}
			int endRow = w.rowAt(w.length() - 1);
			int endCol = w.colAt(w.length() - 1);
			if (w.row() < 0 || w.col() < 0 || endRow >= size || endCol >= size) {
				violations.add("word out of bounds: " + w.word());
				inBounds = false;
				continue;
			}
			for (int i = 0; i < w.length(); i++) {
				if (layout.letterAt(w.rowAt(i), w.colAt(i)) != w.word().charAt(i)) {
					violations.add("letter mismatch in " + w.word() + " at index " + i);
					break;
				}
			}
		}
		return inBounds;
	}

	/** 칸에서 길이 2 이상인 모든 가로/세로 글자 구간이 배치된 단어와 정확히 일치해야 한다. */
	private static void checkNoStrayRuns(GridLayout layout, List<String> violations) {
		Set<String> placed = new HashSet<>();
		for (PlacedWord w : layout.words()) {
			placed.add(runKey(w.row(), w.col(), w.direction(), w.word()));
		}
		Set<String> runs = new HashSet<>();
		for (Direction d : Direction.values()) {
			collectRuns(layout, d, runs);
		}
		for (String run : runs) {
			if (!placed.contains(run)) {
				violations.add("unintended letter run: " + run);
			}
		}
		for (String p : placed) {
			if (!runs.contains(p)) {
				violations.add("placed word is not a maximal run: " + p);
			}
		}
	}

	private static void collectRuns(GridLayout layout, Direction d, Set<String> runs) {
		int size = layout.size();
		for (int r = 0; r < size; r++) {
			for (int c = 0; c < size; c++) {
				if (layout.letterAt(r, c) == 0) {
					continue;
				}
				int pr = r - d.rowStep();
				int pc = c - d.colStep();
				boolean startsRun = pr < 0 || pc < 0 || layout.letterAt(pr, pc) == 0;
				if (!startsRun) {
					continue;
				}
				StringBuilder sb = new StringBuilder();
				int rr = r;
				int cc = c;
				while (rr < size && cc < size && layout.letterAt(rr, cc) != 0) {
					sb.append(layout.letterAt(rr, cc));
					rr += d.rowStep();
					cc += d.colStep();
				}
				if (sb.length() >= 2) {
					runs.add(runKey(r, c, d, sb.toString()));
				}
			}
		}
	}

	private static String runKey(int row, int col, Direction d, String text) {
		return d + "@" + row + "," + col + ":" + text;
	}

	/** 모든 단어가 다른 단어와 교차하고, 교차 관계로 전체가 하나로 연결되어야 한다. */
	private static void checkCrossingAndConnectivity(GridLayout layout, List<String> violations) {
		List<PlacedWord> words = layout.words();
		Map<Long, List<Integer>> wordsByCell = new HashMap<>();
		for (int i = 0; i < words.size(); i++) {
			PlacedWord w = words.get(i);
			for (int k = 0; k < w.length(); k++) {
				long key = ((long) w.rowAt(k) << 32) | w.colAt(k);
				wordsByCell.computeIfAbsent(key, x -> new ArrayList<>()).add(i);
			}
		}
		List<Set<Integer>> neighbors = new ArrayList<>();
		for (int i = 0; i < words.size(); i++) {
			neighbors.add(new HashSet<>());
		}
		for (List<Integer> sharing : wordsByCell.values()) {
			for (int a : sharing) {
				for (int b : sharing) {
					if (a != b && words.get(a).direction() != words.get(b).direction()) {
						neighbors.get(a).add(b);
					}
				}
			}
		}
		if (words.size() > 1) {
			for (int i = 0; i < words.size(); i++) {
				if (neighbors.get(i).isEmpty()) {
					violations.add("word does not cross any other word: " + words.get(i).word());
				}
			}
		}
		Set<Integer> seen = new HashSet<>(Set.of(0));
		Deque<Integer> queue = new ArrayDeque<>(List.of(0));
		while (!queue.isEmpty()) {
			for (int next : neighbors.get(queue.poll())) {
				if (seen.add(next)) {
					queue.add(next);
				}
			}
		}
		if (seen.size() != words.size()) {
			violations.add("grid is not connected");
		}
	}

	private static void checkNumbering(GridLayout layout, List<String> violations) {
		Map<String, Integer> expected = new HashMap<>();
		for (PlacedWord w : GridNumbering.assign(layout.words())) {
			expected.put(w.direction() + ":" + w.word(), w.number());
		}
		for (PlacedWord w : layout.words()) {
			Integer exp = expected.get(w.direction() + ":" + w.word());
			if (exp != null && exp != w.number()) {
				violations.add("wrong number for " + w.word() + ": expected " + exp + " but was " + w.number());
			}
		}
	}
}
