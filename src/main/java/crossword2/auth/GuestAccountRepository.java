package crossword2.auth;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestAccountRepository extends JpaRepository<GuestAccount, UUID> {
}
