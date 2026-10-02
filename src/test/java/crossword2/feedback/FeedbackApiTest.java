package crossword2.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;
import crossword2.auth.OwnerType;
import crossword2.puzzle.Puzzle;
import crossword2.puzzle.PuzzleRepository;
import crossword2.word.Word;
import crossword2.word.WordRepository;

@SpringBootTest
@AutoConfigureMockMvc
class FeedbackApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	FeedbackRepository feedbacks;

	@Autowired
	FeedbackReplyRepository replies;

	@Autowired
	FeedbackAttachmentRepository attachments;

	@Autowired
	AttachmentStore store;

	@Autowired
	PuzzleRepository puzzles;

	@Autowired
	WordRepository words;

	Puzzle puzzle;
	Word reported;

	@BeforeEach
	void setUp() {
		Long id = puzzles.findAll().stream().map(Puzzle::getId).sorted().findFirst().orElseThrow();
		puzzle = puzzles.findWithEntriesById(id).orElseThrow();
		reported = puzzle.getEntries().get(0).getWord();
	}

	private static String uniqueEmail() {
		return "fb" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	private Tokens member() throws Exception {
		return AuthTestClient.signup(mvc, uniqueEmail(), "secret123", "문의회원");
	}

	private static String json(String type, String title, String content) {
		return "{\"type\":\"" + type + "\"" + (title == null ? "" : ",\"title\":\"" + title + "\"")
				+ (content == null ? "" : ",\"content\":\"" + content + "\"") + "}";
	}

	private ResultActions postJson(Tokens caller, String body) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/feedback").contentType(MediaType.APPLICATION_JSON).content(body);
		if (caller != null) {
			request = request.header("Authorization", caller.bearer());
		}
		return mvc.perform(request);
	}

	private ResultActions postMultipart(Tokens caller, String body, MockMultipartFile screenshot) throws Exception {
		MockMultipartHttpServletRequestBuilder request = multipart("/api/feedback")
				.file(new MockMultipartFile("data", "", "application/json", body.getBytes(StandardCharsets.UTF_8)));
		if (screenshot != null) {
			request.file(screenshot);
		}
		if (caller != null) {
			request.header("Authorization", caller.bearer());
		}
		return mvc.perform(request);
	}

	private static MockMultipartFile shot(String originalName, String contentType, byte[] bytes) {
		return new MockMultipartFile("screenshot", originalName, contentType, bytes);
	}

	private long idOf(ResultActions created) throws Exception {
		return ((Number) JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	private String fetch(String url, Tokens caller) throws Exception {
		return mvc.perform(get(url).header("Authorization", caller.bearer())).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
	}

	private String wordReport(Long puzzleId, Long wordId, String reason, String extra) {
		return "{\"type\":\"WORD_ERROR\",\"puzzleId\":" + puzzleId + ",\"wordId\":" + wordId + ",\"reason\":\"" + reason
				+ "\"" + (extra == null ? "" : "," + extra) + "}";
	}

	// ---------- 작성 ----------

	@Test
	void aGuestCanWriteButCannotReceiveReplies() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "문의손님");

		ResultActions result = postJson(guest, json("GENERAL", "질문이 있어요", "어떻게 하나요?"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.replyAvailable").value(false))
				.andExpect(jsonPath("$.status").value("RECEIVED")).andExpect(jsonPath("$.hasAttachment").value(false));

		Feedback saved = feedbacks.findById(idOf(result)).orElseThrow();
		assertThat(saved.getAuthorType()).isEqualTo(OwnerType.GUEST);
		assertThat(saved.getAuthorId()).isEqualTo(guest.ownerId());
		assertThat(saved.getTitle()).isEqualTo("질문이 있어요");
	}

	@Test
	void aMemberCanReceiveReplies() throws Exception {
		Tokens member = member();

		postJson(member, json("SUGGESTION", "제안", "이런 기능이 있으면 좋겠어요")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.replyAvailable").value(true));
	}

	@Test
	void anonymousVisitorsMustStartAsGuestFirst() throws Exception {
		postJson(null, json("GENERAL", "제목", "내용")).andExpect(status().isUnauthorized());
		postMultipart(null, json("GENERAL", "제목", "내용"), null).andExpect(status().isUnauthorized());
	}

	@ParameterizedTest
	@EnumSource(FeedbackType.class)
	void everyTypeIsAccepted(FeedbackType type) throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "유형손님");

		postJson(guest, json(type.name(), "제목", "내용")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.type").value(type.name()));
	}

	@Test
	void textIsTrimmed() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "공백손님");

		long id = idOf(postJson(guest, json("GENERAL", "  앞뒤 공백  ", "\\n  내용  \\n")));

		Feedback saved = feedbacks.findById(id).orElseThrow();
		assertThat(saved.getTitle()).isEqualTo("앞뒤 공백");
		assertThat(saved.getContent()).isEqualTo("내용");
	}

	@Test
	void contentMayHaveLineBreaks() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "줄바꿈손님");

		long id = idOf(postJson(guest, json("BUG", "여러 줄", "첫째 줄\\n둘째 줄")));

		assertThat(feedbacks.findById(id).orElseThrow().getContent()).isEqualTo("첫째 줄\n둘째 줄");
	}

	@Test
	void validationRejectsBadInput() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "검증손님");

		postJson(guest, "{\"title\":\"제목\",\"content\":\"내용\"}").andExpect(status().isBadRequest()); // 유형 없음
		postJson(guest, "{\"type\":\"NOPE\",\"title\":\"제목\",\"content\":\"내용\"}").andExpect(status().isBadRequest());
		postJson(guest, json("GENERAL", "   ", "내용")).andExpect(status().isBadRequest());
		postJson(guest, json("GENERAL", "제목", "   ")).andExpect(status().isBadRequest());
		postJson(guest, json("GENERAL", null, "내용")).andExpect(status().isBadRequest());
		postJson(guest, json("GENERAL", "제목", null)).andExpect(status().isBadRequest());
		postJson(guest, json("GENERAL", "가".repeat(101), "내용")).andExpect(status().isBadRequest());
		postJson(guest, json("GENERAL", "제목", "가".repeat(2001))).andExpect(status().isBadRequest());
		postJson(guest, json("GENERAL", "제목\\n둘째줄", "내용")).andExpect(status().isBadRequest()); // 제목에 줄바꿈 불가
		postJson(guest, json("GENERAL", "제목", "내용\\u0000널")).andExpect(status().isBadRequest()); // 제어 문자
		postJson(guest, "{\"type\":\"GENERAL\",\"title\":\"제목\",\"content\":\"내용\",\"screen\":\"<script>\"}")
				.andExpect(status().isBadRequest());
		postJson(guest, "not json").andExpect(status().isBadRequest());
	}

	@Test
	void theLongestAllowedTextIsAccepted() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "최대손님");

		postJson(guest, json("GENERAL", "가".repeat(100), "나".repeat(2000))).andExpect(status().isCreated());
	}

	@Test
	void anUnknownPuzzleIsRejected() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "퍼즐손님");

		postJson(guest, "{\"type\":\"BUG\",\"title\":\"제목\",\"content\":\"내용\",\"puzzleId\":99999999}")
				.andExpect(status().isBadRequest());
	}

	@Test
	void deviceInfoIsAttachedAutomatically() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "기기손님");

		long id = idOf(mvc.perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", guest.bearer()).header("User-Agent", "TestBrowser/1.0 (Windows)")
				.content("{\"type\":\"BUG\",\"title\":\"제목\",\"content\":\"내용\",\"screen\":\"390x844@3\",\"puzzleId\":"
						+ puzzle.getId() + "}")).andExpect(status().isCreated()));

		Feedback saved = feedbacks.findById(id).orElseThrow();
		assertThat(saved.getDeviceInfo()).isEqualTo("TestBrowser/1.0 (Windows) | screen=390x844@3");
		assertThat(saved.getPuzzleId()).isEqualTo(puzzle.getId());
	}

	@Test
	void theUserAgentIsCleanedAndLimited() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "에이전트손님");
		String longAgent = "A".repeat(600);

		long id = idOf(mvc.perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", guest.bearer()).header("User-Agent", longAgent)
				.content(json("GENERAL", "제목", "내용"))).andExpect(status().isCreated()));

		assertThat(feedbacks.findById(id).orElseThrow().getDeviceInfo()).hasSize(250);
	}

	// ---------- 퍼즐 내 빠른 신고 ----------

	@Test
	void aQuickWordReportNeedsOnlyThePuzzleWordAndReason() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "신고손님");

		long id = idOf(postJson(guest, wordReport(puzzle.getId(), reported.getId(), "WRONG_MEANING", null))
				.andExpect(status().isCreated()));

		Feedback saved = feedbacks.findById(id).orElseThrow();
		assertThat(saved.getType()).isEqualTo(FeedbackType.WORD_ERROR);
		assertThat(saved.getPuzzleId()).isEqualTo(puzzle.getId());
		assertThat(saved.getWordId()).isEqualTo(reported.getId());
		assertThat(saved.getReason()).isEqualTo(FeedbackReason.WRONG_MEANING);
		assertThat(saved.getTitle()).startsWith("[신고] ").contains(reported.getKorean());
		assertThat(saved.getContent()).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(FeedbackReason.class)
	void everyReasonIsAccepted(FeedbackReason reason) throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "사유손님");

		postJson(guest, wordReport(puzzle.getId(), reported.getId(), reason.name(), "\"content\":\"덧붙이는 말\""))
				.andExpect(status().isCreated());
	}

	@Test
	void aWordReportIsCheckedAgainstThePuzzle() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "검증신고손님");
		Set<Long> inPuzzle = puzzle.getEntries().stream().map(e -> e.getWord().getId()).collect(Collectors.toSet());
		Word foreign = words.findAll().stream().filter(w -> !inPuzzle.contains(w.getId())).findFirst().orElseThrow();

		postJson(guest, wordReport(puzzle.getId(), foreign.getId(), "TYPO", null)).andExpect(status().isBadRequest()); // 퍼즐에 없는 단어
		postJson(guest, wordReport(99999999L, reported.getId(), "TYPO", null)).andExpect(status().isBadRequest()); // 없는 퍼즐
		postJson(guest, "{\"type\":\"WORD_ERROR\",\"puzzleId\":" + puzzle.getId() + ",\"wordId\":" + reported.getId() + "}")
				.andExpect(status().isBadRequest()); // 사유 없음
		postJson(guest, "{\"type\":\"WORD_ERROR\",\"wordId\":" + reported.getId() + ",\"reason\":\"TYPO\"}")
				.andExpect(status().isBadRequest()); // 퍼즐 ID 없음
		postJson(guest, wordReport(puzzle.getId(), reported.getId(), "TYPO", null).replace("WORD_ERROR", "BUG"))
				.andExpect(status().isBadRequest()); // 단어 신고가 아닌 유형에 wordId
		postJson(guest, "{\"type\":\"WORD_ERROR\",\"title\":\"제목\",\"content\":\"내용\",\"reason\":\"TYPO\"}")
				.andExpect(status().isBadRequest()); // wordId 없이 사유만
		postJson(guest, wordReport(puzzle.getId(), reported.getId(), "NOT_A_REASON", null)).andExpect(status().isBadRequest());
	}

	@Test
	void aGeneralWordErrorWithoutAWordNeedsTitleAndContent() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "일반단어손님");

		postJson(guest, json("WORD_ERROR", "단어가 이상해요", "어떤 단어인지 설명")).andExpect(status().isCreated());
		postJson(guest, "{\"type\":\"WORD_ERROR\"}").andExpect(status().isBadRequest());
	}

	// ---------- 정답 보호 ----------

	@Test
	void theEnglishAnswerNeverAppearsInResponsesToTheAuthor() throws Exception {
		Tokens member = member();
		String english = reported.getEnglish();

		String created = postJson(member, wordReport(puzzle.getId(), reported.getId(), "WRONG_MEANING", "\"content\":\"뜻이 이상해요\""))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String mine = fetch("/api/feedback/mine", member);

		assertThat(created.toLowerCase()).doesNotContain("\"" + english + "\"");
		assertThat(mine).doesNotContain("\"" + english + "\"");
		assertThat(mine.toLowerCase()).doesNotContain("\"english\"");
		assertThat((String) JsonPath.read(mine, "$.items[0].wordKorean")).isEqualTo(reported.getKorean());
		assertThat(((Number) JsonPath.read(mine, "$.items[0].wordId")).longValue()).isEqualTo(reported.getId());
	}

	// ---------- 내 문의 내역 ----------

	@Test
	void guestsCannotListTheirFeedback() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "목록손님");
		postJson(guest, json("GENERAL", "제목", "내용")).andExpect(status().isCreated());

		mvc.perform(get("/api/feedback/mine").header("Authorization", guest.bearer())).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("MEMBER_ONLY"));
		mvc.perform(get("/api/feedback/unread-count").header("Authorization", guest.bearer())).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/feedback/mine")).andExpect(status().isUnauthorized());
	}

	@Test
	void membersSeeOnlyTheirOwnFeedbackNewestFirst() throws Exception {
		Tokens alice = member();
		Tokens bob = member();
		postJson(alice, json("GENERAL", "첫째", "내용")).andExpect(status().isCreated());
		postJson(alice, json("BUG", "둘째", "내용")).andExpect(status().isCreated());
		postJson(bob, json("GENERAL", "밥의 문의", "내용")).andExpect(status().isCreated());

		String aliceList = fetch("/api/feedback/mine", alice);
		String bobList = fetch("/api/feedback/mine", bob);

		assertThat(JsonPath.<List<String>>read(aliceList, "$.items[*].title")).containsExactly("둘째", "첫째");
		assertThat(JsonPath.<List<String>>read(bobList, "$.items[*].title")).containsExactly("밥의 문의");
		assertThat((String) JsonPath.read(aliceList, "$.items[0].status")).isEqualTo("RECEIVED");
	}

	@Test
	void anEmptyHistoryIsFine() throws Exception {
		Tokens member = member();

		mvc.perform(get("/api/feedback/mine").header("Authorization", member.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.unreadCount").value(0));
	}

	// ---------- 답변과 읽음 처리 ----------

	private long reply(long feedbackId, String content) {
		return replies.save(new FeedbackReply(feedbackId, 1L, content, Instant.now())).getId();
	}

	@Test
	void repliesAreListedWithUnreadFlagsAndCounted() throws Exception {
		Tokens member = member();
		long feedbackId = idOf(postJson(member, json("GENERAL", "질문", "내용")));
		reply(feedbackId, "첫 번째 답변");
		reply(feedbackId, "두 번째 답변");

		String mine = fetch("/api/feedback/mine", member);

		assertThat(JsonPath.<List<String>>read(mine, "$.items[0].replies[*].content")).containsExactly("첫 번째 답변", "두 번째 답변");
		assertThat(JsonPath.<List<Boolean>>read(mine, "$.items[0].replies[*].unread")).containsExactly(true, true);
		assertThat(((Number) JsonPath.read(mine, "$.unreadCount")).intValue()).isEqualTo(2);
		assertThat(((Number) JsonPath.read(fetch("/api/feedback/unread-count", member), "$.count")).intValue()).isEqualTo(2);
	}

	@Test
	void openingAReplyMarksItReadOnlyTheFirstTime() throws Exception {
		Tokens member = member();
		long feedbackId = idOf(postJson(member, json("GENERAL", "질문", "내용")));
		long replyId = reply(feedbackId, "답변");
		reply(feedbackId, "또 다른 답변");

		String first = mvc.perform(patch("/api/feedback/replies/{id}/read", replyId).header("Authorization", member.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(false)).andReturn().getResponse()
				.getContentAsString();
		String second = mvc.perform(patch("/api/feedback/replies/{id}/read", replyId).header("Authorization", member.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat((String) JsonPath.read(first, "$.readAt")).isNotBlank();
		assertThat((String) JsonPath.read(second, "$.readAt")).as("처음 읽은 시각이 유지된다").isEqualTo(JsonPath.read(first, "$.readAt"));
		assertThat(((Number) JsonPath.read(fetch("/api/feedback/unread-count", member), "$.count")).intValue()).isEqualTo(1);
		assertThat(replies.findById(replyId).orElseThrow().getReadAt()).isNotNull();
	}

	@Test
	void nobodyElseCanReadMyReplies() throws Exception {
		Tokens owner = member();
		Tokens other = member();
		Tokens guest = AuthTestClient.guest(mvc, "엿보는손님");
		long replyId = reply(idOf(postJson(owner, json("GENERAL", "질문", "내용"))), "비밀 답변");

		mvc.perform(patch("/api/feedback/replies/{id}/read", replyId).header("Authorization", other.bearer()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPLY_NOT_FOUND"));
		mvc.perform(patch("/api/feedback/replies/{id}/read", replyId).header("Authorization", guest.bearer()))
				.andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/feedback/replies/{id}/read", replyId)).andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/feedback/replies/{id}/read", 99999999).header("Authorization", owner.bearer()))
				.andExpect(status().isNotFound());

		assertThat(replies.findById(replyId).orElseThrow().getReadAt()).as("남이 열어도 읽음 처리되지 않는다").isNull();
		assertThat(fetch("/api/feedback/mine", other)).doesNotContain("비밀 답변");
	}

	// ---------- 스크린샷 첨부 ----------

	@Test
	void aPngScreenshotIsStoredUnderARandomNameAndTheOriginalNameIsIgnored() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "첨부손님");
		byte[] png = TestImages.png(80, 60);

		ResultActions result = postMultipart(guest, json("BUG", "화면이 이상해요", "스크린샷을 첨부해요"),
				shot("../../내 스크린샷 (1).png", "image/png", png)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.hasAttachment").value(true));

		FeedbackAttachment saved = attachments.findByFeedbackId(idOf(result)).orElseThrow();
		assertThat(saved.getStoredName()).matches("[0-9a-f-]{36}\\.png");
		assertThat(saved.getContentType()).isEqualTo("image/png");
		assertThat(store.exists(saved.getStoredName())).isTrue();
		assertThat(store.load(saved.getStoredName())).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
		assertThat(saved.getSize()).isEqualTo(store.load(saved.getStoredName()).length);
	}

	@Test
	void aJpegScreenshotIsAccepted() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "제이펙손님");

		ResultActions result = postMultipart(guest, json("BUG", "제목", "내용"), shot("a.jpg", "image/jpeg", TestImages.jpeg(50, 50)))
				.andExpect(status().isCreated());

		assertThat(attachments.findByFeedbackId(idOf(result)).orElseThrow().getStoredName()).endsWith(".jpg");
	}

	@Test
	void metadataAndTrailingDataAreStrippedFromTheStoredFile() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "정화손님");
		byte[] tricky = TestImages.withTrailer(TestImages.jpegWithExif(40, 40, "PRIVATE-LOCATION"), "<script>x</script>");

		ResultActions result = postMultipart(guest, json("BUG", "제목", "내용"), shot("p.jpg", "image/jpeg", tricky))
				.andExpect(status().isCreated());

		byte[] stored = store.load(attachments.findByFeedbackId(idOf(result)).orElseThrow().getStoredName());
		assertThat(TestImages.contains(stored, "PRIVATE-LOCATION")).isFalse();
		assertThat(TestImages.contains(stored, "<script>")).isFalse();
	}

	@Test
	void disguisedAndUnsupportedFilesAreRejectedAndLeaveNothingBehind() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "위장손님");
		long before = feedbacks.count();
		List<MockMultipartFile> bad = List.of(
				shot("screenshot.png", "image/png", "just some text".getBytes(StandardCharsets.UTF_8)),
				shot("evil.png", "image/png", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)),
				shot("vector.png", "image/png", "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8)),
				shot("anim.gif", "image/gif", TestImages.gif(10, 10)),
				shot("photo.png", "image/png", TestImages.bmp(10, 10)),
				shot("doc.pdf", "application/pdf", "%PDF-1.7 not really a pdf".getBytes(StandardCharsets.UTF_8)),
				shot("broken.png", "image/png", TestImages.truncated(TestImages.png(200, 200), 60)),
				shot("bomb.png", "image/png", TestImages.pngClaiming(30_000, 30_000)));

		for (MockMultipartFile file : bad) {
			postMultipart(guest, json("BUG", "제목", "내용"), file).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_ATTACHMENT"));
		}

		assertThat(feedbacks.count()).as("거부된 첨부가 있는 문의는 저장되지 않는다").isEqualTo(before);
	}

	@Test
	void aTooLargeFileIsRejectedWithAClearError() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "큰파일손님");
		byte[] huge = new byte[2 * 1024 * 1024 + 1];
		System.arraycopy(TestImages.png(10, 10), 0, huge, 0, 20);

		postMultipart(guest, json("BUG", "제목", "내용"), shot("big.png", "image/png", huge))
				.andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("ATTACHMENT_TOO_LARGE"));
	}

	@Test
	void anEmptyFilePartMeansNoAttachment() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "빈파일손님");

		postMultipart(guest, json("BUG", "제목", "내용"), shot("", "application/octet-stream", new byte[0]))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.hasAttachment").value(false));
	}

	@Test
	void aMissingDataPartIsABadRequest() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "데이터없음손님");

		mvc.perform(multipart("/api/feedback").file(shot("a.png", "image/png", TestImages.png(10, 10)))
				.header("Authorization", guest.bearer())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	// ---------- 첨부 내려받기 ----------

	@Test
	void onlyTheAuthorCanDownloadTheScreenshotWithSafeHeaders() throws Exception {
		Tokens author = AuthTestClient.guest(mvc, "작성손님");
		Tokens other = AuthTestClient.guest(mvc, "다른손님");
		long id = idOf(postMultipart(author, json("BUG", "제목", "내용"), shot("a.png", "image/png", TestImages.png(30, 30))));

		byte[] body = mvc.perform(get("/api/feedback/{id}/attachment", id).header("Authorization", author.bearer()))
				.andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("sandbox")))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
				.andReturn().getResponse().getContentAsByteArray();

		assertThat(body).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
		mvc.perform(get("/api/feedback/{id}/attachment", id).header("Authorization", other.bearer()))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/feedback/{id}/attachment", id)).andExpect(status().isUnauthorized());
	}

	@Test
	void aFeedbackWithoutAScreenshotHasNothingToDownload() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "없는첨부손님");
		long id = idOf(postJson(guest, json("GENERAL", "제목", "내용")));

		mvc.perform(get("/api/feedback/{id}/attachment", id).header("Authorization", guest.bearer()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
		mvc.perform(get("/api/feedback/{id}/attachment", 99999999).header("Authorization", guest.bearer()))
				.andExpect(status().isNotFound());
	}

	@Test
	void myHistoryShowsWhetherAScreenshotWasAttached() throws Exception {
		Tokens member = member();
		postMultipart(member, json("BUG", "사진 있음", "내용"), shot("a.png", "image/png", TestImages.png(20, 20)))
				.andExpect(status().isCreated());
		postJson(member, json("BUG", "사진 없음", "내용")).andExpect(status().isCreated());

		String mine = fetch("/api/feedback/mine", member);

		assertThat(JsonPath.<List<Boolean>>read(mine, "$.items[*].hasAttachment")).containsExactly(false, true);
	}
}
