package crossword2.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crossword2.common.ApiException;

@Service
@Transactional(readOnly = true)
public class AccountService {

	private final UserRepository users;
	private final GuestAccountRepository guests;

	public AccountService(UserRepository users, GuestAccountRepository guests) {
		this.users = users;
		this.guests = guests;
	}

	/** 토큰의 주체가 아직 존재하는지 확인하고 계정을 돌려준다. 없으면 401. */
	public Account resolve(Owner owner) {
		try {
			if (owner.type() == OwnerType.GUEST) {
				return guests.findById(UUID.fromString(owner.id())).map(Account::guest).orElseThrow(this::gone);
			}
			return users.findById(Long.parseLong(owner.id())).map(Account::member).orElseThrow(this::gone);
		} catch (IllegalArgumentException e) {
			throw gone();
		}
	}

	private ApiException gone() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_FOUND", "account no longer exists");
	}
}
