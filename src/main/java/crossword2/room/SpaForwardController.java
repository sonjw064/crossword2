package crossword2.room;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 초대 링크(/room/{code})로 들어오면 앱을 열어 준다. 프론트가 주소를 읽어 해당 방 화면으로 이동한다. */
@Controller
class SpaForwardController {

	@GetMapping("/room/{code}")
	String room() {
		return "forward:/index.html";
	}
}
