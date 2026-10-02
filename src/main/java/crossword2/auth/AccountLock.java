package crossword2.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import crossword2.common.ApiException;

/**
 * 계정(회원/게스트) 행 잠금. 같은 사용자의 기록 갱신(풀이 종료, 게스트 이전)을 직렬화한다.
 * 잠금 순서는 항상 <b>계정 → 풀이 세션</b>이다(게스트 이전은 게스트 계정 → 회원 계정 → 데이터). 이 순서를 지키면 교착이 생기지 않는다.
 */
@Service
public class AccountLock {

	private final UserRepository users;
	private final GuestAccountRepository guests;

	public AccountLock(UserRepository users, GuestAccountRepository guests) {
		this.users = users;
		this.guests = guests;
	}

	/** 이미 진행 중인 트랜잭션 안에서만 호출한다. 계정이 없으면 아무것도 하지 않는다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lock(Owner owner) {
		try {
			if (owner.type() == OwnerType.GUEST) {
				guests.findForUpdate(UUID.fromString(owner.id()));
			} else {
				users.findForUpdate(Long.parseLong(owner.id()));
			}
		} catch (IllegalArgumentException e) {
			// 형식이 잘못된 주체는 잠글 계정이 없다
		}
	}

	/**
	 * 새 기록(풀이 세션, 문의)을 만들기 전에 계정 행을 잠그고 아직 쓸 수 있는 계정인지 확인한다. 이전된 게스트나 없는 계정이면 401.
	 * 잠금을 얻은 뒤에 확인하므로, 게스트 이전이 먼저 끝났다면 주인 없는 데이터가 새로 생기지 않는다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockActive(Owner owner) {
		try {
			if (owner.type() == OwnerType.GUEST) {
				GuestAccount guest = guests.findForUpdate(UUID.fromString(owner.id())).orElseThrow(AccountLock::gone);
				if (guest.getMigratedToUserId() != null) {
					throw new ApiException(HttpStatus.UNAUTHORIZED, "GUEST_MIGRATED", "this guest has been migrated to a member");
				}
			} else {
				users.findForUpdate(Long.parseLong(owner.id())).orElseThrow(AccountLock::gone);
			}
		} catch (IllegalArgumentException e) {
			throw gone();
		}
	}

	/**
	 * 계정 행을 잠그고 지금 이 사람의 계정을 돌려준다. 게스트가 그 사이 회원으로 이전됐다면 이전된 회원 계정(도 함께 잠근다).
	 * 이미 끝난 일의 기록(대련 결과 등)을 남길 때 쓴다: 이전된 게스트의 이름으로 주인 없는 데이터를 만들지 않고 회원 소유로 남긴다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Owner lockCurrent(Owner owner) {
		try {
			if (owner.type() == OwnerType.GUEST) {
				GuestAccount guest = guests.findForUpdate(UUID.fromString(owner.id())).orElse(null);
				if (guest != null && guest.getMigratedToUserId() != null) {
					users.findForUpdate(guest.getMigratedToUserId());
					return new Owner(OwnerType.MEMBER, String.valueOf(guest.getMigratedToUserId()));
				}
			} else {
				users.findForUpdate(Long.parseLong(owner.id()));
			}
		} catch (IllegalArgumentException e) {
			// 형식이 잘못된 주체는 잠글 계정이 없다
		}
		return owner;
	}

	private static ApiException gone() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_FOUND", "account no longer exists");
	}
}
