package crossword2.grid;

import java.time.Duration;

/**
 * 생성기 설정.
 * 탐색 범위는 {@code maxNodesPerAttempt}(노드 수)로 제한해 결과를 결정적으로 유지하고,
 * {@code timeLimit}은 안전장치로만 쓴다(초과 시 결과를 바꾸지 않고 예외를 던진다).
 */
public record GeneratorConfig(int maxAttempts, int maxNodesPerAttempt, double minPlacedRatio, Duration timeLimit) {

	public GeneratorConfig {
		if (maxAttempts < 1 || maxNodesPerAttempt < 1) {
			throw new IllegalArgumentException("maxAttempts and maxNodesPerAttempt must be positive");
		}
		if (minPlacedRatio <= 0 || minPlacedRatio > 1) {
			throw new IllegalArgumentException("minPlacedRatio must be in (0, 1]");
		}
		if (timeLimit == null || timeLimit.isNegative()) {
			throw new IllegalArgumentException("timeLimit must not be negative");
		}
	}

	public static GeneratorConfig defaults() {
		return new GeneratorConfig(30, 1_500, 0.5, Duration.ofSeconds(5));
	}

	public GeneratorConfig withTimeLimit(Duration limit) {
		return new GeneratorConfig(maxAttempts, maxNodesPerAttempt, minPlacedRatio, limit);
	}
}
