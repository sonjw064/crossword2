package crossword2.puzzle;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crossword2.auth.OwnerType;
import jakarta.persistence.LockModeType;

public interface PlaySessionRepository extends JpaRepository<PlaySession, UUID> {

	/** 세션 상태를 바꾸는 요청은 이 메서드로 행 잠금을 잡아 같은 세션의 요청을 직렬화한다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from PlaySession s where s.id = :id")
	Optional<PlaySession> findForUpdate(@Param("id") UUID id);

	/** 게스트 소유 세션을 회원 소유로 옮긴다(게스트 → 회원 이전). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update PlaySession s set s.ownerType = :toType, s.ownerId = :toId where s.ownerType = :fromType and s.ownerId = :fromId")
	int reassignOwner(@Param("fromType") OwnerType fromType, @Param("fromId") String fromId,
			@Param("toType") OwnerType toType, @Param("toId") String toId);
}
