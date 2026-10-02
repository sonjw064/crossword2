package crossword2.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import crossword2.auth.Owner;
import crossword2.auth.OwnerType;
import crossword2.common.ApiException;
import crossword2.room.Match.SubmitStatus;

/** 레이스 점수 규칙과 종료/순위 규칙(순수 단위 테스트). */
class MatchTest {

	private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
	private static final Owner A = new Owner(OwnerType.MEMBER, "1");
	private static final Owner B = new Owner(OwnerType.GUEST, "g-b");
	private static final Owner C = new Owner(OwnerType.MEMBER, "3");
	private static final Owner D = new Owner(OwnerType.MEMBER, "4");

	/** 정답: cat(3글자, 항목 1), horse(5글자, 항목 2). 카운트다운 3초 뒤인 T0+3부터 제출 가능, 제한 시간 60초. */
	private static Match match(Owner... owners) {
		List<Match.Participant> ps = new java.util.ArrayList<>();
		for (int i = 0; i < owners.length; i++) {
			ps.add(new Match.Participant(i + 1, owners[i], "p" + (i + 1)));
		}
		return new Match(List.of(new Match.Entry(1, 11, "cat"), new Match.Entry(2, 12, "horse")), ps, T0.plusSeconds(3),
				Duration.ofSeconds(60));
	}

	private static void assertError(Throwable t, int status, String code) {
		assertThat(t).isInstanceOfSatisfying(ApiException.class, e -> {
			assertThat(e.status().value()).isEqualTo(status);
			assertThat(e.code()).isEqualTo(code);
		});
	}

	private static Instant at(double seconds) {
		return T0.plusMillis((long) (seconds * 1000));
	}

	// ---- 점수 ----

	@Test
	void aCorrectWordScoresTenPointsPerLetter() {
		Match m = match(A, B);

		Match.SubmitResult r = m.submit(1, 2, "horse", at(4));

		assertThat(r.status()).isEqualTo(SubmitStatus.CORRECT);
		assertThat(r.gained()).isEqualTo(50);
		assertThat(r.score()).isEqualTo(50);
		assertThat(r.solved()).isEqualTo(1);
		assertThat(r.completed()).isFalse();
	}

	@Test
	void answersAreCaseInsensitiveAndTrimmed() {
		Match m = match(A, B);

		assertThat(m.submit(1, 1, "  CaT ", at(4)).status()).isEqualTo(SubmitStatus.CORRECT);
	}

	@Test
	void wrongAnswersCostNothing() {
		Match m = match(A, B);

		Match.SubmitResult wrong = m.submit(1, 1, "dog", at(4));

		assertThat(wrong.status()).isEqualTo(SubmitStatus.WRONG);
		assertThat(wrong.gained()).isZero();
		assertThat(wrong.score()).isZero();
	}

	@Test
	void aWordOfTheWrongLengthIsIncompleteNotWrong() {
		Match m = match(A, B);

		assertThat(m.submit(1, 1, "ca", at(4)).status()).isEqualTo(SubmitStatus.INCOMPLETE);
		assertThat(m.submit(1, 1, "", at(4.5)).status()).isEqualTo(SubmitStatus.INCOMPLETE);
	}

	@Test
	void solvingTheSameWordTwiceGivesNoMorePoints() {
		Match m = match(A, B);
		m.submit(1, 1, "cat", at(4));

		Match.SubmitResult again = m.submit(1, 1, "cat", at(5));

		assertThat(again.status()).isEqualTo(SubmitStatus.ALREADY_SOLVED);
		assertThat(again.score()).isEqualTo(30);
	}

	@Test
	void finishOrderBonusesAre100Then60Then30ThenNothing() {
		Match m = match(A, B, C, D);
		int[] expectedBonus = { 100, 60, 30, 0 };
		for (int i = 0; i < 4; i++) {
			int playerId = i + 1;
			m.submit(playerId, 1, "cat", at(10 + i * 2));
			Match.SubmitResult last = m.submit(playerId, 2, "horse", at(11 + i * 2));
			assertThat(last.completed()).isTrue();
			assertThat(last.bonus()).as("player " + playerId).isEqualTo(expectedBonus[i]);
			assertThat(last.score()).isEqualTo(80 + expectedBonus[i]);
		}
	}

	@Test
	void aFinishedPlayerCannotSubmitAnymore() {
		Match m = match(A, B);
		m.submit(1, 1, "cat", at(4));
		m.submit(1, 2, "horse", at(5));

		assertThatThrownBy(() -> m.submit(1, 1, "cat", at(6))).satisfies(t -> assertError(t, 409, "INVALID_STATE"));
	}

	// ---- 제출 규칙 ----

	@Test
	void submissionsAreRefusedDuringTheCountdown() {
		Match m = match(A, B);

		assertThatThrownBy(() -> m.submit(1, 1, "cat", at(2.9))).satisfies(t -> assertError(t, 409, "MATCH_NOT_STARTED"));
		assertThat(m.submit(1, 1, "cat", at(3)).status()).isEqualTo(SubmitStatus.CORRECT);
	}

	@Test
	void submissionsAreRefusedAfterTheDeadline() {
		Match m = match(A, B);

		assertThat(m.submit(1, 1, "cat", at(62.9)).status()).isEqualTo(SubmitStatus.CORRECT);
		assertThatThrownBy(() -> m.submit(2, 1, "cat", at(63))).satisfies(t -> assertError(t, 409, "MATCH_OVER"));
	}

	@Test
	void atMostThreeSubmissionsPerSecond() {
		Match m = match(A, B);
		m.submit(1, 1, "xxx", at(4.0));
		m.submit(1, 1, "xxx", at(4.1));
		m.submit(1, 1, "xxx", at(4.2));

		assertThatThrownBy(() -> m.submit(1, 1, "cat", at(4.3))).satisfies(t -> assertError(t, 429, "RATE_LIMITED"));
		assertThat(m.submit(1, 1, "cat", at(5.0)).status()).as("1초가 지나면 다시 가능").isEqualTo(SubmitStatus.CORRECT);
	}

	@Test
	void theRateLimitIsPerPlayer() {
		Match m = match(A, B);
		for (int i = 0; i < 3; i++) {
			m.submit(1, 1, "xxx", at(4 + i * 0.1));
		}

		assertThat(m.submit(2, 1, "cat", at(4.4)).status()).isEqualTo(SubmitStatus.CORRECT);
	}

	@Test
	void anUnknownEntryIsRejected() {
		Match m = match(A, B);

		assertThatThrownBy(() -> m.submit(1, 99, "cat", at(4))).satisfies(t -> assertError(t, 400, "INVALID_ENTRY"));
	}

	@Test
	void anAbandonedPlayerCannotSubmit() {
		Match m = match(A, B);
		m.abandon(2);

		assertThatThrownBy(() -> m.submit(2, 1, "cat", at(4))).satisfies(t -> assertError(t, 409, "INVALID_STATE"));
	}

	// ---- 종료 ----

	@Test
	void theMatchIsOverWhenTimeRunsOut() {
		Match m = match(A, B);

		assertThat(m.isOver(at(62.9))).isFalse();
		assertThat(m.isOver(at(63))).isTrue();
	}

	@Test
	void theMatchIsOverWhenEveryoneFinishedOrLeft() {
		Match m = match(A, B);
		m.submit(1, 1, "cat", at(4));
		m.submit(1, 2, "horse", at(5));
		assertThat(m.isOver(at(6))).as("B가 아직 푸는 중").isFalse();

		m.abandon(2);

		assertThat(m.isOver(at(7))).isTrue();
	}

	@Test
	void aFinishedPlayerCannotBeMadeAbandoned() {
		Match m = match(A, B);
		m.submit(1, 1, "cat", at(4));
		m.submit(1, 2, "horse", at(5));

		m.abandon(1);

		assertThat(m.standings().get(0).abandoned()).isFalse();
	}

	// ---- 순위 ----

	@Test
	void rankingIsByScoreThenEarlierLastCorrectAnswerThenSeat() {
		Match m = match(A, B, C, D);
		m.submit(3, 1, "cat", at(10)); // C 30점, 10초
		m.submit(2, 1, "cat", at(8)); // B 30점, 8초 (더 일찍)
		m.submit(1, 2, "horse", at(20)); // A 50점

		List<Match.Standing> ranking = m.end();

		assertThat(ranking).extracting(Match.Standing::playerId).containsExactly(1, 2, 3, 4);
		assertThat(ranking).extracting(Match.Standing::rank).containsExactly(1, 2, 3, 4);
		assertThat(ranking.get(3).score()).isZero();
	}

	@Test
	void abandonedPlayersStayInTheRankingWithTheirScore() {
		Match m = match(A, B);
		m.submit(2, 2, "horse", at(5));
		m.abandon(2);

		List<Match.Standing> ranking = m.end();

		assertThat(ranking.get(0).playerId()).isEqualTo(2);
		assertThat(ranking.get(0).abandoned()).isTrue();
	}

	// ---- 공개 상태에는 정답이 없다 ----

	@Test
	void theViewAndToStringNeverContainTheAnswers() {
		Match m = match(A, B);
		m.submit(1, 1, "cat", at(4));

		String shown = m.view(at(5)).toString() + m.entryList() + m.standings();

		assertThat(shown).doesNotContain("cat").doesNotContain("horse");
		assertThat(m.view(at(5)).progress()).extracting(RoomView.ProgressView::solved).containsExactly(1, 0);
		assertThat(m.view(at(5)).progress()).allSatisfy(p -> assertThat(p.rank()).isNull());
	}

	@Test
	void ranksAppearInTheViewOnlyAfterTheMatchEnded() {
		Match m = match(A, B);
		m.submit(1, 1, "cat", at(4));
		m.end();

		assertThat(m.view(at(70)).progress()).extracting(RoomView.ProgressView::rank).containsExactly(1, 2);
		assertThat(m.view(at(70)).ended()).isTrue();
	}
}
