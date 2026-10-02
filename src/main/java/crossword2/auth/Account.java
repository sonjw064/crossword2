package crossword2.auth;

/** 토큰 발급과 /api/me 응답에 쓰는 계정 요약. 게스트는 role과 email이 없다. */
public record Account(Owner owner, String nickname, String email, Role role) {

	static Account guest(GuestAccount guest) {
		return new Account(new Owner(OwnerType.GUEST, guest.getId().toString()), guest.getNickname(), null, null);
	}

	static Account member(User user) {
		return new Account(new Owner(OwnerType.MEMBER, String.valueOf(user.getId())), user.getNickname(),
				user.getEmail(), user.getRole());
	}
}
