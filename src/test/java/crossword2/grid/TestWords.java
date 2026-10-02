package crossword2.grid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

final class TestWords {

	static final List<String> POOL = List.of(
			"cat", "dog", "bird", "fish", "horse", "rabbit", "tiger", "lion", "monkey", "elephant",
			"giraffe", "turtle", "dolphin", "penguin", "butterfly", "squirrel", "kangaroo", "apple",
			"bread", "milk", "rice", "cake", "banana", "cheese", "tomato", "potato", "orange", "carrot",
			"noodle", "sandwich", "chicken", "pumpkin", "book", "desk", "pencil", "teacher", "student",
			"lesson", "homework", "notebook", "eraser", "library", "history", "science", "river",
			"mountain", "forest", "ocean", "island", "rainbow", "thunder", "storm", "desert", "valley",
			"kitchen", "garden", "blanket", "pillow", "mirror", "happy", "angry", "tired", "hungry");

	private TestWords() {
	}

	/** seed로 섞은 풀에서 최대 길이 이하인 단어를 count개 고른다. */
	static List<String> pick(int count, int maxLength, long seed) {
		List<String> candidates = new ArrayList<>(POOL.stream().filter(w -> w.length() <= maxLength).toList());
		Collections.shuffle(candidates, new Random(seed));
		return candidates.subList(0, Math.min(count, candidates.size()));
	}
}
