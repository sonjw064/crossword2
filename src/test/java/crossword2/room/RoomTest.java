package crossword2.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import crossword2.auth.Owner;
import crossword2.auth.OwnerType;
import crossword2.common.ApiException;
import crossword2.word.Difficulty;

class RoomTest {

	private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
	private static final Duration GRACE = Duration.ofSeconds(60);

	private static final Owner HOST = new Owner(OwnerType.MEMBER, "1");
	private static final Owner BOB = new Owner(OwnerType.GUEST, "bob-guest-id");
	private static final Owner CAT = new Owner(OwnerType.MEMBER, "3");
	private static final Owner DAN = new Owner(OwnerType.GUEST, "dan-guest-id");

	private static RoomSettings settings(int maxPlayers) {
		return RoomSettings.validated(Difficulty.EASY, null, 7, 300, maxPlayers, RoomMode.RACE, EnumSet.of(RoomMode.RACE));
	}

	private static Room room(int maxPlayers) {
		Room room = new Room("ABC234", HOST, "방장", settings(maxPlayers), T0);
		room.join(HOST, "방장", "s-host", T0);
		return room;
	}

	private static void assertError(Throwable thrown, int status, String code) {
		assertThat(thrown).isInstanceOfSatisfying(ApiException.class, e -> {
			assertThat(e.status().value()).isEqualTo(status);
			assertThat(e.code()).isEqualTo(code);
		});
	}

	// ---- 입장 ----

	@Test
	void theHostSeatExistsFromTheStartButIsDisconnectedUntilTheHostJoins() {
		Room fresh = new Room("ABC234", HOST, "방장", settings(4), T0);

		assertThat(fresh.view().players()).singleElement().satisfies(p -> {
			assertThat(p.host()).isTrue();
			assertThat(p.connected()).isFalse();
		});

		fresh.join(HOST, "방장", "s1", T0);
		assertThat(fresh.view().players().get(0).connected()).isTrue();
	}

	@Test
	void playersJoinInOrderWithRoomLocalIds() {
		Room room = room(4);

		Room.JoinOutcome bob = room.join(BOB, "밥", "s-bob", T0);
		Room.JoinOutcome cat = room.join(CAT, "캣", "s-cat", T0);

		assertThat(bob.rejoined()).isFalse();
		assertThat(bob.playerId()).isEqualTo(2);
		assertThat(cat.playerId()).isEqualTo(3);
		assertThat(room.view().players()).extracting(RoomView.PlayerView::nickname).containsExactly("방장", "밥", "캣");
	}

	@Test
	void theRoomRefusesPlayersBeyondItsCapacity() {
		Room room = room(2);
		room.join(BOB, "밥", "s-bob", T0);

		assertThatThrownBy(() -> room.join(CAT, "캣", "s-cat", T0)).satisfies(t -> assertError(t, 409, "ROOM_FULL"));
		assertThat(room.playerCount()).isEqualTo(2);
		assertThat(room.joinable()).isFalse();
	}

	@Test
	void joiningAgainReconnectsInsteadOfTakingASecondSeat() {
		Room room = room(2);
		room.join(BOB, "밥", "s-old", T0);
		room.disconnect("s-old", T0.plusSeconds(5));

		Room.JoinOutcome again = room.join(BOB, "밥", "s-new", T0.plusSeconds(10));

		assertThat(again.rejoined()).isTrue();
		assertThat(again.playerId()).isEqualTo(2);
		assertThat(room.playerCount()).isEqualTo(2);
		assertThat(room.view().players().get(1).connected()).isTrue();
	}

	@Test
	void nobodyNewCanJoinOnceTheMatchHasStarted() {
		Room room = room(4);
		room.join(BOB, "밥", "s-bob", T0);
		room.setReady(BOB, true);
		room.checkCanStart(HOST);
		room.markStarted(42L, T0.plusSeconds(1));

		assertThatThrownBy(() -> room.join(CAT, "캣", "s-cat", T0.plusSeconds(2)))
				.satisfies(t -> assertError(t, 409, "ROOM_IN_PROGRESS"));
		assertThat(room.join(BOB, "밥", "s-bob2", T0.plusSeconds(3)).rejoined()).as("참가자의 재접속은 가능").isTrue();
	}

	// ---- 퇴장과 방장 위임 ----

	@Test
	void whenTheHostLeavesTheEarliestConnectedPlayerBecomesHost() {
		Room room = room(4);
		room.join(BOB, "밥", "s-bob", T0);
		room.join(CAT, "캣", "s-cat", T0);
		room.disconnect("s-bob", T0.plusSeconds(1)); // 밥은 연결이 끊김 → 캣이 우선

		Room.LeaveOutcome left = room.leave(HOST, T0.plusSeconds(2));

		assertThat(left.removed()).isTrue();
		assertThat(left.newHost()).isEqualTo(CAT);
		assertThat(room.isHost(CAT)).isTrue();
		assertThat(room.view().players()).filteredOn(RoomView.PlayerView::host).extracting(RoomView.PlayerView::nickname)
				.containsExactly("캣");
	}

	@Test
	void leavingAsANonHostKeepsTheHostAndFreesTheSeat() {
		Room room = room(2);
		room.join(BOB, "밥", "s-bob", T0);

		Room.LeaveOutcome left = room.leave(BOB, T0);

		assertThat(left.newHost()).isNull();
		assertThat(room.isHost(HOST)).isTrue();
		assertThat(room.joinable()).isTrue();
		assertThat(room.leave(BOB, T0).removed()).as("두 번 나가도 안전").isFalse();
	}

	@Test
	void anEmptiedRoomIsExpired() {
		Room room = room(2);

		room.leave(HOST, T0);

		assertThat(room.isExpired(T0, Duration.ofMinutes(5))).isTrue();
	}

	// ---- 준비와 시작 ----

	@Test
	void startRequiresTheHostTwoPlayersAndEveryoneReadyAndConnected() {
		Room room = room(4);
		assertThatThrownBy(() -> room.checkCanStart(HOST)).satisfies(t -> assertError(t, 409, "NOT_ENOUGH_PLAYERS"));

		room.join(BOB, "밥", "s-bob", T0);
		room.join(CAT, "캣", "s-cat", T0);
		assertThatThrownBy(() -> room.checkCanStart(BOB)).satisfies(t -> assertError(t, 403, "NOT_HOST"));
		assertThatThrownBy(() -> room.checkCanStart(HOST)).satisfies(t -> assertError(t, 409, "NOT_ALL_READY"));

		room.setReady(BOB, true);
		assertThatThrownBy(() -> room.checkCanStart(HOST)).as("캣이 아직 준비 안 함").satisfies(t -> assertError(t, 409, "NOT_ALL_READY"));
		room.setReady(CAT, true);
		room.checkCanStart(HOST); // 이제 통과
	}

	@Test
	void aDisconnectedPlayerLosesTheirReadyStateAndBlocksTheStart() {
		Room room = room(3);
		room.join(BOB, "밥", "s-bob", T0);
		room.setReady(BOB, true);
		room.checkCanStart(HOST);

		room.disconnect("s-bob", T0.plusSeconds(1));

		assertThatThrownBy(() -> room.checkCanStart(HOST)).satisfies(t -> assertError(t, 409, "NOT_ALL_READY"));
		assertThatThrownBy(() -> room.setReady(BOB, true)).satisfies(t -> assertError(t, 409, "INVALID_STATE"));
	}

	@Test
	void theHostCountsAsReadyAndCannotToggleIt() {
		Room room = room(2);

		room.setReady(HOST, false);

		assertThat(room.view().players().get(0).ready()).isTrue();
	}

	@Test
	void startedRoomsExposeThePuzzleIdOnly() {
		Room room = room(2);
		room.join(BOB, "밥", "s-bob", T0);
		room.setReady(BOB, true);
		assertThat(room.view().puzzleId()).isNull();

		room.markStarted(77L, T0.plusSeconds(1));

		assertThat(room.status()).isEqualTo(RoomStatus.PLAYING);
		assertThat(room.view().puzzleId()).isEqualTo(77L);
		assertThatThrownBy(() -> room.setReady(BOB, false)).satisfies(t -> assertError(t, 409, "INVALID_STATE"));
	}

	@Test
	void onlyParticipantsCanSendCommands() {
		Room room = room(2);

		assertThatThrownBy(() -> room.setReady(CAT, true)).satisfies(t -> assertError(t, 403, "NOT_IN_ROOM"));
		assertThatThrownBy(() -> room.updateSettings(CAT, settings(3))).satisfies(t -> assertError(t, 403, "NOT_IN_ROOM"));
		assertThatThrownBy(() -> room.checkCanStart(CAT)).satisfies(t -> assertError(t, 403, "NOT_IN_ROOM"));
	}

	// ---- 설정 변경 ----

	@Test
	void onlyTheHostCanChangeSettingsAndItResetsEveryonesReadyState() {
		Room room = room(4);
		room.join(BOB, "밥", "s-bob", T0);
		room.setReady(BOB, true);
		RoomSettings changed = RoomSettings.validated(Difficulty.HARD, "animals", 10, 600, 4, RoomMode.RACE,
				EnumSet.of(RoomMode.RACE));

		assertThatThrownBy(() -> room.updateSettings(BOB, changed)).satisfies(t -> assertError(t, 403, "NOT_HOST"));
		room.updateSettings(HOST, changed);

		assertThat(room.settings()).isEqualTo(changed);
		assertThat(room.view().players().get(1).ready()).as("설정이 바뀌면 준비가 풀린다").isFalse();
	}

	@Test
	void settingsCannotShrinkBelowTheCurrentPlayerCountOrChangeAfterTheStart() {
		Room room = room(4);
		room.join(BOB, "밥", "s-bob", T0);
		room.join(CAT, "캣", "s-cat", T0);

		assertThatThrownBy(() -> room.updateSettings(HOST, settings(2))).satisfies(t -> assertError(t, 400, "SETTINGS_INVALID"));

		room.setReady(BOB, true);
		room.setReady(CAT, true);
		room.markStarted(1L, T0);
		assertThatThrownBy(() -> room.updateSettings(HOST, settings(4))).satisfies(t -> assertError(t, 409, "INVALID_STATE"));
	}

	// ---- 연결 끊김과 정리 ----

	@Test
	void aStaleDisconnectFromAnOldSessionIsIgnored() {
		Room room = room(2);
		room.join(BOB, "밥", "s-old", T0);
		room.join(BOB, "밥", "s-new", T0.plusSeconds(1)); // 새 연결로 교체

		assertThat(room.disconnect("s-old", T0.plusSeconds(2))).isEmpty();
		assertThat(room.view().players().get(1).connected()).isTrue();
		assertThat(room.disconnect("s-new", T0.plusSeconds(3))).contains(BOB);
	}

	@Test
	void aDisconnectedPlayerIsRemovedAfterTheGracePeriodButNotBefore() {
		Room room = room(3);
		room.join(BOB, "밥", "s-bob", T0);
		room.disconnect("s-bob", T0.plusSeconds(10));

		assertThat(room.sweep(T0.plusSeconds(69), GRACE).removed()).as("유예 시간 안에는 자리 유지").isEmpty();
		Room.SweepOutcome swept = room.sweep(T0.plusSeconds(70), GRACE);

		assertThat(swept.removed()).containsExactly(BOB);
		assertThat(room.contains(BOB)).isFalse();
	}

	@Test
	void reconnectingWithinTheGraceKeepsTheSeat() {
		Room room = room(3);
		room.join(BOB, "밥", "s-bob", T0);
		room.disconnect("s-bob", T0.plusSeconds(10));
		room.join(BOB, "밥", "s-bob2", T0.plusSeconds(50));

		assertThat(room.sweep(T0.plusSeconds(500), GRACE).removed()).isEmpty();
		assertThat(room.contains(BOB)).isTrue();
	}

	@Test
	void ifTheHostNeverConnectsTheSeatIsRemovedAndTheRoomExpires() {
		Room room = new Room("ABC234", HOST, "방장", settings(2), T0);

		Room.SweepOutcome swept = room.sweep(T0.plusSeconds(60), GRACE);

		assertThat(swept.removed()).containsExactly(HOST);
		assertThat(room.isExpired(T0.plusSeconds(60), Duration.ofMinutes(5))).isTrue();
	}

	@Test
	void aSweptHostPassesTheRoleOn() {
		Room room = room(3);
		room.join(BOB, "밥", "s-bob", T0);
		room.disconnect("s-host", T0.plusSeconds(5));

		Room.SweepOutcome swept = room.sweep(T0.plusSeconds(65), GRACE);

		assertThat(swept.removed()).containsExactly(HOST);
		assertThat(swept.newHost()).isEqualTo(BOB);
		assertThat(room.isHost(BOB)).isTrue();
	}

	@Test
	void duringAMatchDisconnectedPlayersKeepTheirSeats() {
		Room room = room(3);
		room.join(BOB, "밥", "s-bob", T0);
		room.setReady(BOB, true);
		room.markStarted(5L, T0);
		room.disconnect("s-bob", T0.plusSeconds(1));

		assertThat(room.sweep(T0.plusSeconds(3600), GRACE).removed()).isEmpty();
		assertThat(room.contains(BOB)).isTrue();
	}

	@Test
	void aRoomWithNoConnectedPlayersExpiresAfterTheTtl() {
		Room room = room(2);
		room.join(BOB, "밥", "s-bob", T0);
		room.disconnect("s-host", T0.plusSeconds(10));
		room.disconnect("s-bob", T0.plusSeconds(20));
		Duration ttl = Duration.ofMinutes(5);

		assertThat(room.isExpired(T0.plusSeconds(20).plus(ttl).minusSeconds(1), ttl)).isFalse();
		assertThat(room.isExpired(T0.plusSeconds(20).plus(ttl), ttl)).isTrue();

		room.join(BOB, "밥", "s-bob2", T0.plusSeconds(100));
		assertThat(room.isExpired(T0.plusSeconds(100).plus(ttl).plusSeconds(60), ttl)).as("연결이 돌아오면 만료되지 않는다").isFalse();
	}

	// ---- 공개 상태 ----

	@Test
	void theBroadcastViewNeverExposesAccountIds() {
		Room room = room(3);
		room.join(BOB, "밥", "s-bob", T0);

		String text = room.view().toString();

		assertThat(text).doesNotContain("bob-guest-id").doesNotContain("MEMBER").doesNotContain("GUEST");
		assertThat(room.view().players()).extracting(RoomView.PlayerView::playerId).containsExactly(1, 2);
	}

	@Test
	void everyChangeBumpsTheVersionSoClientsCanDropStaleUpdates() {
		Room room = room(3);
		long v0 = room.version();

		room.join(BOB, "밥", "s-bob", T0);
		long v1 = room.version();
		room.setReady(BOB, true);
		long v2 = room.version();
		room.leave(BOB, T0);

		assertThat(List.of(v0, v1, v2, room.version())).isSorted().doesNotHaveDuplicates();
	}
}
