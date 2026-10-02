package crossword2.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "guest_accounts")
public class GuestAccount {

	@Id
	private UUID id;

	@Column(nullable = false, length = 20)
	private String nickname;

	@Column(nullable = false)
	private Instant createdAt;

	/** 회원으로 이전되면 그 사용자 id. 이전은 한 번만 가능하다. */
	private Long migratedToUserId;

	protected GuestAccount() {
	}

	public GuestAccount(String nickname, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.nickname = nickname;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public String getNickname() {
		return nickname;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Long getMigratedToUserId() {
		return migratedToUserId;
	}
}
