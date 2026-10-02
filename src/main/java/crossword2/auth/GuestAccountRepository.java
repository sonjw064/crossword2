package crossword2.auth;

import java.util.UUID;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface GuestAccountRepository extends JpaRepository<GuestAccount, UUID> {

	/** 같은 게스트를 동시에 이전하려는 요청을 직렬화한다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from GuestAccount g where g.id = :id")
	Optional<GuestAccount> findForUpdate(@Param("id") UUID id);
}
