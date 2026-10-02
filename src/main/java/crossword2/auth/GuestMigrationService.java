package crossword2.auth;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crossword2.common.ApiException;

/** 게스트 → 회원 이전. 게스트 ID당 한 번만 가능하며, 게스트 토큰으로 소유를 증명해야 한다. */
@Service
public class GuestMigrationService {

	private final GuestAccountRepository guests;
	private final RefreshTokenRepository refreshTokens;
	private final List<GuestDataMigrator> migrators;
	private final Clock clock;

	public GuestMigrationService(GuestAccountRepository guests, RefreshTokenRepository refreshTokens,
			List<GuestDataMigrator> migrators, Clock clock) {
		this.guests = guests;
		this.refreshTokens = refreshTokens;
		this.migrators = migrators;
		this.clock = clock;
	}

	/**
	 * 요청에 실린 게스트 토큰이 {@code guestId}의 주인임을 확인한다. 게스트 ID만 알아서는 이전할 수 없다.
	 *
	 * @param proof 요청의 Bearer 토큰(없으면 null)
	 */
	public UUID verifyProof(Jwt proof, String guestId) {
		UUID id;
		try {
			id = UUID.fromString(guestId);
		} catch (IllegalArgumentException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "guestId is not a valid id");
		}
		Owner owner = Owner.fromJwt(proof);
		if (owner == null || owner.type() != OwnerType.GUEST || !owner.id().equals(id.toString())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "GUEST_PROOF_INVALID",
					"a guest token for this guestId is required to migrate its data");
		}
		return id;
	}

	/**
	 * 게스트의 데이터를 회원으로 옮긴다. 호출한 쪽의 트랜잭션에 참여하므로, 회원 생성과 함께 원자적으로 처리된다.
	 * 게스트 행을 잠가 같은 게스트를 동시에 이전하려는 요청 중 하나만 성공하게 한다.
	 */
	@Transactional
	public void migrate(UUID guestId, Long userId) {
		GuestAccount guest = guests.findForUpdate(guestId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GUEST_NOT_FOUND", "guest not found"));
		if (guest.getMigratedToUserId() != null) {
			throw new ApiException(HttpStatus.CONFLICT, "GUEST_ALREADY_MIGRATED",
					"this guest has already been migrated to a member");
		}
		guest.markMigrated(userId);
		Owner from = new Owner(OwnerType.GUEST, guestId.toString());
		Owner to = new Owner(OwnerType.MEMBER, String.valueOf(userId));
		refreshTokens.revokeAllFor(OwnerType.GUEST, from.id(), clock.instant());
		for (GuestDataMigrator migrator : migrators) {
			migrator.migrate(from, to);
		}
	}
}
