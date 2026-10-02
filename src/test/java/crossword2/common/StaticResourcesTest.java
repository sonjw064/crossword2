package crossword2.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class StaticResourcesTest {

	@Autowired
	MockMvc mvc;

	@ParameterizedTest
	@ValueSource(strings = { "/", "/index.html", "/css/style.css", "/js/app.js", "/js/api.js", "/js/grid-model.js",
			"/js/select.js", "/js/play.js", "/js/result.js", "/js/http.js", "/js/auth.js", "/js/auth-views.js",
			"/js/progress-views.js", "/js/review-model.js", "/js/labels.js", "/js/dom.js", "/js/settings.js",
			"/js/feedback-model.js", "/js/feedback-views.js" })
	void frontendFilesAreServedWithoutLogin(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isOk());
	}

	@Test
	void indexLoadsTheAppAsAModule() throws Exception {
		mvc.perform(get("/index.html")).andExpect(content().string(org.hamcrest.Matchers.containsString("type=\"module\"")));
	}

	@Test
	void otherPathsStayClosed() throws Exception {
		mvc.perform(get("/internal/secret.txt")).andExpect(status().isUnauthorized());
	}
}
