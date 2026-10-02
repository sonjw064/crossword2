package crossword2.progress;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crossword2.auth.OwnerType;
import crossword2.puzzle.PlayStatus;

public interface PlayRecordRepository extends JpaRepository<PlayRecord, Long> {

	boolean existsBySessionId(java.util.UUID sessionId);

	Page<PlayRecord> findByOwnerTypeAndOwnerId(OwnerType ownerType, String ownerId, Pageable pageable);

	long countByOwnerTypeAndOwnerIdAndStatus(OwnerType ownerType, String ownerId, PlayStatus status);

	@Query("select avg(r.elapsedSec) from PlayRecord r where r.ownerType = :type and r.ownerId = :id and r.status = :status")
	Double averageElapsed(@Param("type") OwnerType type, @Param("id") String id, @Param("status") PlayStatus status);

	@Query("select min(r.elapsedSec) from PlayRecord r where r.ownerType = :type and r.ownerId = :id and r.status = :status")
	Long bestElapsed(@Param("type") OwnerType type, @Param("id") String id, @Param("status") PlayStatus status);

	@Query("select distinct r.puzzleId from PlayRecord r where r.ownerType = :type and r.ownerId = :id and r.status = :status order by r.puzzleId")
	List<Long> distinctPuzzleIds(@Param("type") OwnerType type, @Param("id") String id, @Param("status") PlayStatus status);

	/** 게스트 → 회원 이전. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update PlayRecord r set r.ownerType = :toType, r.ownerId = :toId where r.ownerType = :fromType and r.ownerId = :fromId")
	int reassignOwner(@Param("fromType") OwnerType fromType, @Param("fromId") String fromId,
			@Param("toType") OwnerType toType, @Param("toId") String toId);
}
