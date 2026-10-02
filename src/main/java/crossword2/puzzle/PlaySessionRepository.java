package crossword2.puzzle;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaySessionRepository extends JpaRepository<PlaySession, UUID> {
}
