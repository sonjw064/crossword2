package crossword2.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** refresh 토큰. 원문은 저장하지 않고 SHA-256 해시만 저장한다. 같은 로그인에서 교체된 토큰들은 familyId를 공유한다. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 64)
	private String tokenHash;

	@Column(nullable = false)
	private UUID familyId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OwnerType ownerType;

	@Column(nullable = false, length = 36)
	private String ownerId;

	@Column(nullable = false)
	private Instant expiresAt;

	private Instant revokedAt;

	@Column(nullable = false)
	private Instant createdAt;

	protected RefreshToken() {
	}

	public RefreshToken(String tokenHash, UUID familyId, Owner owner, Instant createdAt, Instant expiresAt) {
		this.tokenHash = tokenHash;
		this.familyId = familyId;
		this.ownerType = owner.type();
		this.ownerId = owner.id();
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public void revoke(Instant now) {
		this.revokedAt = now;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public UUID getFamilyId() {
		return familyId;
	}

	public Owner owner() {
		return new Owner(ownerType, ownerId);
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}
}
