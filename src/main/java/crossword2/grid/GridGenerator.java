package crossword2.grid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import crossword2.grid.GridGenerationException.Reason;

/**
 * 교차 배치 + 백트래킹 그리드 생성기. Spring과 무관한 순수 Java이며, 같은 (단어 목록, 크기, seed, 설정)이면
 * 항상 같은 결과를 낸다. 시간이 아니라 탐색 노드 수로 범위를 제한하기 때문이다.
 */
public final class GridGenerator {

	public static final int MIN_SIZE = 7;
	public static final int MAX_SIZE = 15;
	public static final int MIN_WORDS = 3;

	private static final Pattern VALID_WORD = Pattern.compile("[a-z]{2,}");
	private static final int TIME_CHECK_INTERVAL = 64;
	private static final int BRANCHING = 3;

	private record Option(PlacedWord placement, int crossings) {
	}

	private final GeneratorConfig config;

	public GridGenerator() {
		this(GeneratorConfig.defaults());
	}

	public GridGenerator(GeneratorConfig config) {
		this.config = config;
	}

	public GridLayout generate(List<String> words, int size, long seed) {
		validateInput(words, size);
		long deadline = System.nanoTime() + config.timeLimit().toNanos();

		for (int attempt = 0; attempt < config.maxAttempts(); attempt++) {
			checkDeadline(deadline);
			Random rng = new Random(seed ^ (attempt * 0x9E3779B97F4A7C15L));
			List<String> order = new ArrayList<>(words);
			java.util.Collections.shuffle(order, rng);
			order.sort(Comparator.comparingInt(String::length).reversed());

			List<PlacedWord> placed = new Search(size, order, rng, deadline).run();
			GridLayout layout = GridLayout.fromWords(size, GridNumbering.assign(placed), words.size());
			int minCrossings = (int) Math.floor(config.minCrossingRatio() * layout.words().size());
			if (layout.placedRatio() >= config.minPlacedRatio() && layout.crossings() >= minCrossings) {
				return layout;
			}
		}
		throw new GridGenerationException(Reason.QUALITY_NOT_MET,
				"could not reach the quality target (placed >= " + (int) Math.round(config.minPlacedRatio() * 100)
						+ "% of the words, crossings >= " + config.minCrossingRatio() + " per word) within "
						+ config.maxAttempts() + " attempts");
	}

	private static void checkDeadline(long deadline) {
		if (System.nanoTime() - deadline >= 0) {
			throw new GridGenerationException(Reason.TIMEOUT, "grid generation exceeded the time limit");
		}
	}

	private static void validateInput(List<String> words, int size) {
		if (size < MIN_SIZE || size > MAX_SIZE) {
			throw new GridGenerationException(Reason.INVALID_SIZE,
					"size must be between " + MIN_SIZE + " and " + MAX_SIZE + ": " + size);
		}
		if (words == null || words.size() < MIN_WORDS) {
			throw new GridGenerationException(Reason.TOO_FEW_WORDS, "at least " + MIN_WORDS + " words are required");
		}
		Set<String> seen = new HashSet<>();
		for (String w : words) {
			if (w == null || !VALID_WORD.matcher(w).matches()) {
				throw new GridGenerationException(Reason.INVALID_WORD,
						"words must be lowercase letters a-z, at least 2 long: " + w);
			}
			if (w.length() > size) {
				throw new GridGenerationException(Reason.WORD_TOO_LONG, "word longer than the grid: " + w);
			}
			if (!seen.add(w)) {
				throw new GridGenerationException(Reason.DUPLICATE_WORD, "duplicate word: " + w);
			}
		}
	}

	/** 한 번의 시도: 첫 단어를 고정하고, 매 단계 교차가 많은 위치 상위 몇 개를 DFS(백트래킹)로 시도하며 가장 많이 놓인 해를 기억한다. */
	private final class Search {

		private final int size;
		private final List<String> order;
		private final Random rng;
		private final long deadline;
		private final char[][] board;
		private final boolean[][] across;
		private final boolean[][] down;
		private final List<PlacedWord> current = new ArrayList<>();
		private final Set<String> placedTexts = new HashSet<>();
		private List<PlacedWord> best = List.of();
		private int nodes;
		private boolean stop;

		Search(int size, List<String> order, Random rng, long deadline) {
			this.size = size;
			this.order = order;
			this.rng = rng;
			this.deadline = deadline;
			this.board = new char[size][size];
			this.across = new boolean[size][size];
			this.down = new boolean[size][size];
		}

		List<PlacedWord> run() {
			// 가장 긴 단어 3개 중 하나로 시작해 시도마다 다른 모양이 나오게 한다
			int longest = Math.min(3, order.size());
			String first = order.get(rng.nextInt(longest));
			Direction dir = rng.nextBoolean() ? Direction.ACROSS : Direction.DOWN;
			int mid = size / 2;
			int offset = (size - first.length()) / 2;
			PlacedWord firstWord = dir == Direction.ACROSS
					? new PlacedWord(first, mid, offset, dir, 0)
					: new PlacedWord(first, offset, mid, dir, 0);
			place(firstWord);
			dfs();
			return best;
		}

		private void dfs() {
			if (stop) {
				return;
			}
			if (++nodes > config.maxNodesPerAttempt()) {
				stop = true;
				return;
			}
			if (nodes % TIME_CHECK_INTERVAL == 0) {
				checkDeadline(deadline);
			}
			if (current.size() > best.size()) {
				best = List.copyOf(current);
				if (best.size() == order.size()) {
					stop = true;
					return;
				}
			}
			List<Option> options = options();
			int limit = Math.min(BRANCHING, options.size());
			for (int i = 0; i < limit; i++) {
				PlacedWord choice = options.get(i).placement();
				place(choice);
				dfs();
				unplace(choice);
				if (stop) {
					return;
				}
			}
		}

		/**
		 * 아직 놓지 않은 모든 단어의 유효한 위치를 모아 seed로 섞은 뒤, 교차가 많은 순 → 긴 단어 순으로 안정 정렬한다.
		 * 각 노드에서는 앞쪽 {@value #BRANCHING}개만 시도한다.
		 */
		private List<Option> options() {
			List<Option> result = new ArrayList<>();
			for (String word : order) {
				if (!placedTexts.contains(word)) {
					collectOptions(word, result);
				}
			}
			java.util.Collections.shuffle(result, rng);
			result.sort(Comparator.comparingInt(Option::crossings).reversed()
					.thenComparing(Comparator.comparingInt((Option o) -> o.placement().length()).reversed()));
			return result;
		}

		private void collectOptions(String word, List<Option> result) {
			Set<Integer> seen = new HashSet<>();
			for (int r = 0; r < size; r++) {
				for (int c = 0; c < size; c++) {
					char ch = board[r][c];
					if (ch == 0) {
						continue;
					}
					for (int i = word.indexOf(ch); i >= 0; i = word.indexOf(ch, i + 1)) {
						for (Direction d : Direction.values()) {
							int row = r - d.rowStep() * i;
							int col = c - d.colStep() * i;
							int key = (row * 64 + col) * 2 + d.ordinal();
							if (row < 0 || col < 0 || !seen.add(key)) {
								continue;
							}
							PlacedWord cand = new PlacedWord(word, row, col, d, 0);
							int crossings = crossingsIfPlaced(cand);
							if (crossings >= 1) {
								result.add(new Option(cand, crossings));
							}
						}
					}
				}
			}
		}

		/** 규칙을 어기면 -1, 아니면 교차하는 칸 수. 범위, 글자 일치, 이웃/앞뒤 칸 비어 있음, 같은 방향 겹침 금지를 검사한다. */
		private int crossingsIfPlaced(PlacedWord w) {
			int len = w.length();
			int endRow = w.rowAt(len - 1);
			int endCol = w.colAt(len - 1);
			if (w.row() < 0 || w.col() < 0 || endRow >= size || endCol >= size) {
				return -1;
			}
			Direction d = w.direction();
			if (!isEmptyOrOutside(w.row() - d.rowStep(), w.col() - d.colStep())
					|| !isEmptyOrOutside(endRow + d.rowStep(), endCol + d.colStep())) {
				return -1;
			}
			boolean[][] sameDirUsed = d == Direction.ACROSS ? across : down;
			int crossings = 0;
			for (int i = 0; i < len; i++) {
				int r = w.rowAt(i);
				int c = w.colAt(i);
				char existing = board[r][c];
				if (existing == 0) {
					// 새 글자: 직교 방향 이웃이 비어 있어야 의도치 않은 글자열이 생기지 않는다
					if (!isEmptyOrOutside(r + d.colStep(), c + d.rowStep())
							|| !isEmptyOrOutside(r - d.colStep(), c - d.rowStep())) {
						return -1;
					}
				} else {
					if (existing != w.word().charAt(i) || sameDirUsed[r][c]) {
						return -1;
					}
					crossings++;
				}
			}
			return crossings;
		}

		private boolean isEmptyOrOutside(int r, int c) {
			return r < 0 || c < 0 || r >= size || c >= size || board[r][c] == 0;
		}

		private void place(PlacedWord w) {
			boolean[][] used = w.direction() == Direction.ACROSS ? across : down;
			for (int i = 0; i < w.length(); i++) {
				board[w.rowAt(i)][w.colAt(i)] = w.word().charAt(i);
				used[w.rowAt(i)][w.colAt(i)] = true;
			}
			current.add(w);
			placedTexts.add(w.word());
		}

		/** 다른 방향 단어가 아직 쓰는 칸의 글자는 남기고, 이 단어만 쓰던 칸을 비운다. */
		private void unplace(PlacedWord w) {
			current.remove(current.size() - 1);
			placedTexts.remove(w.word());
			boolean[][] used = w.direction() == Direction.ACROSS ? across : down;
			boolean[][] other = w.direction() == Direction.ACROSS ? down : across;
			for (int i = 0; i < w.length(); i++) {
				int r = w.rowAt(i);
				int c = w.colAt(i);
				used[r][c] = false;
				if (!other[r][c]) {
					board[r][c] = 0;
				}
			}
		}
	}
}
