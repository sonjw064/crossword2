package crossword2.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/** 같은 토큰을 동시에 교체하려는 요청을 직렬화한다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from RefreshToken t where t.tokenHash = :hash")
	Optional<RefreshToken> findByHashForUpdate(@Param("hash") String hash);

	@Modifying
	@Query("delete from RefreshToken t where t.expiresAt < :cutoff")
	int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

	@Modifying
	@Query("update RefreshToken t set t.revokedAt = :now where t.ownerType = :type and t.ownerId = :ownerId and t.revokedAt is null")
	int revokeAllFor(@Param("type") OwnerType type, @Param("ownerId") String ownerId, @Param("now") Instant now);

	@Modifying
	@Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :family and t.revokedAt is null")
	int revokeFamily(@Param("family") UUID family, @Param("now") Instant now);
}
