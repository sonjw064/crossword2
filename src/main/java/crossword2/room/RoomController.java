package crossword2.room;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import crossword2.auth.Owner;
import crossword2.room.RoomDtos.CreateRoomResponse;
import crossword2.room.RoomDtos.RoomInfo;
import crossword2.room.RoomDtos.RoomSettingsRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** 방 만들기와 조회. 입장과 대기실 명령은 웹소켓(STOMP)으로 한다. 로그인(게스트 포함)이 필요하다. */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

	private final RoomService rooms;

	public RoomController(RoomService rooms) {
		this.rooms = rooms;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CreateRoomResponse create(@Valid @RequestBody RoomSettingsRequest request, @AuthenticationPrincipal Jwt jwt,
			HttpServletRequest http) {
		return rooms.create(Owner.fromJwt(jwt), nickname(jwt), request, http.getRemoteAddr());
	}

	@GetMapping("/{code}")
	public RoomInfo get(@PathVariable String code, @AuthenticationPrincipal Jwt jwt) {
		return rooms.info(code, Owner.fromJwt(jwt));
	}

	/** 끝난 경기의 순위와 단어 카드. 그 경기의 참가자만 볼 수 있다. */
	@GetMapping("/{code}/result")
	public RoomDtos.MatchResultView result(@PathVariable String code, @AuthenticationPrincipal Jwt jwt) {
		return rooms.result(code, Owner.fromJwt(jwt));
	}

	static String nickname(Jwt jwt) {
		String nickname = jwt.getClaimAsString("nickname");
		return nickname == null || nickname.isBlank() ? "player" : nickname;
	}
}
