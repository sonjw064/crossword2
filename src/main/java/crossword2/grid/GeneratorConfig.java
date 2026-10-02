package crossword2.grid;

import java.time.Duration;

/**
 * 생성기 설정.
 * 품질 기준은 배치 비율({@code minPlacedRatio})과 교차 수({@code minCrossingRatio}: 배치 단어당 교차 칸 수,
 * 기준값은 내림해 적용)이다. 연결된 그리드는 교차가 항상 (단어 수 - 1) 이상이므로, 0.8은 퇴보 방지용 하한이고
 * 1.0 이상은 순환(루프)이 있는 그리드를 요구한다.
 * 탐색 범위는 {@code maxNodesPerAttempt}(노드 수)로 제한해 결과를 결정적으로 유지하고,
 * {@code timeLimit}은 안전장치로만 쓴다(초과 시 결과를 바꾸지 않고 예외를 던진다).
 */
public record GeneratorConfig(int maxAttempts, int maxNodesPerAttempt, double minPlacedRatio,
		double minCrossingRatio, Duration timeLimit) {

	public GeneratorConfig {
		if (maxAttempts < 1 || maxNodesPerAttempt < 1) {
			throw new IllegalArgumentException("maxAttempts and maxNodesPerAttempt must be positive");
		}
		if (minPlacedRatio <= 0 || minPlacedRatio > 1) {
			throw new IllegalArgumentException("minPlacedRatio must be in (0, 1]");
		}
		if (minCrossingRatio < 0) {
			throw new IllegalArgumentException("minCrossingRatio must not be negative");
		}
		if (timeLimit == null || timeLimit.isNegative()) {
			throw new IllegalArgumentException("timeLimit must not be negative");
		}
	}

	public static GeneratorConfig defaults() {
		return new GeneratorConfig(30, 1_500, 0.5, 0.8, Duration.ofSeconds(5));
	}

	public GeneratorConfig withTimeLimit(Duration limit) {
		return new GeneratorConfig(maxAttempts, maxNodesPerAttempt, minPlacedRatio, minCrossingRatio, limit);
	}
}
