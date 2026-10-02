package crossword2.puzzle;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PlaySessionRepository extends JpaRepository<PlaySession, UUID> {

	/** 세션 상태를 바꾸는 요청은 이 메서드로 행 잠금을 잡아 같은 세션의 요청을 직렬화한다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from PlaySession s where s.id = :id")
	Optional<PlaySession> findForUpdate(@Param("id") UUID id);
}
