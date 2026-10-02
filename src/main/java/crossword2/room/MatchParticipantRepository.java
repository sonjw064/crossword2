package crossword2.room;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import crossword2.auth.OwnerType;

public interface MatchParticipantRepository extends JpaRepository<MatchParticipant, Long> {

	List<MatchParticipant> findByMatchIdOrderByRank(Long matchId);

	List<MatchParticipant> findByOwnerTypeAndOwnerId(OwnerType ownerType, String ownerId);

	/** 게스트 → 회원 이전. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update MatchParticipant p set p.ownerType = :toType, p.ownerId = :toId where p.ownerType = :fromType and p.ownerId = :fromId")
	int reassignOwner(@Param("fromType") OwnerType fromType, @Param("fromId") String fromId,
			@Param("toType") OwnerType toType, @Param("toId") String toId);
}
