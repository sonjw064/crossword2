package crossword2.feedback;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import crossword2.auth.GuestAccount;
import crossword2.auth.GuestAccountRepository;
import crossword2.auth.Owner;
import crossword2.auth.OwnerType;
import crossword2.auth.RateLimiter;
import crossword2.common.ApiException;
import crossword2.feedback.FeedbackDtos.AttachmentFile;
import crossword2.feedback.FeedbackDtos.CreateFeedbackRequest;
import crossword2.feedback.FeedbackDtos.FeedbackCreated;
import crossword2.feedback.FeedbackDtos.FeedbackView;
import crossword2.feedback.FeedbackDtos.MineResponse;
import crossword2.feedback.FeedbackDtos.ReplyView;
import crossword2.puzzle.Puzzle;
import crossword2.puzzle.PuzzleRepository;
import crossword2.word.Word;
import crossword2.word.WordRepository;

@Service
public class FeedbackService {

	private static final Duration HOUR = Duration.ofHours(1);
	private static final int MAX_TITLE = 100;
	private static final int MAX_USER_AGENT = 250;
	/** 줄바꿈/탭을 뺀 제어 문자. */
	private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\r\\t]]");
	private static final Pattern ANY_CONTROL = Pattern.compile("\\p{Cc}");
	private static final Pattern SCREEN = Pattern.compile(FeedbackDtos.SCREEN_FORMAT);

	private final FeedbackRepository feedbacks;
	private final FeedbackReplyRepository replies;
	private final FeedbackAttachmentRepository attachments;
	private final AttachmentStore store;
	private final FeedbackProperties props;
	private final RateLimiter rateLimiter;
	private final PuzzleRepository puzzles;
	private final WordRepository words;
	private final GuestAccountRepository guests;
	private final Clock clock;

	public FeedbackService(FeedbackRepository feedbacks, FeedbackReplyRepository replies,
			FeedbackAttachmentRepository attachments, AttachmentStore store, FeedbackProperties props,
			RateLimiter rateLimiter, PuzzleRepository puzzles, WordRepository words, GuestAccountRepository guests,
			Clock clock) {
		this.feedbacks = feedbacks;
		this.replies = replies;
		this.attachments = attachments;
		this.store = store;
		this.props = props;
		this.rateLimiter = rateLimiter;
		this.puzzles = puzzles;
		this.words = words;
		this.guests = guests;
		this.clock = clock;
	}

	// ---------- 작성 ----------

	/**
	 * 문의를 저장한다. 게스트와 회원 모두 쓸 수 있고, 게스트는 답변을 받을 수 없다(replyAvailable=false).
	 * 한도 검사 → 입력 검증 → 첨부 검증/재인코딩 → 저장 순서이며, 파일은 트랜잭션이 실패하면 지운다.
	 */
	@Transactional
	public FeedbackCreated create(Owner owner, CreateFeedbackRequest request, MultipartFile screenshot,
			String userAgent, String clientIp) {
		rateLimiter.check("feedback-owner:" + owner.type() + ":" + owner.id(), props.perOwnerPerHour(), HOUR);
		rateLimiter.check("feedback-ip:" + clientIp, props.perIpPerHour(), HOUR);
		requireActiveAccount(owner);

		boolean quickReport = request.wordId() != null;
		Word word = null;
		if (quickReport) {
			word = validateWordReport(request);
		} else {
			if (request.reason() != null) {
				throw validation("reason can only be used with a word report (wordId)");
			}
			if (request.puzzleId() != null && !puzzles.existsById(request.puzzleId())) {
				throw validation("puzzle not found");
			}
		}

		String title = text(request.title(), false);
		String content = text(request.content(), true);
		if (quickReport) {
			if (title == null) {
				title = truncate("[신고] " + word.getKorean(), MAX_TITLE);
			}
			if (content == null) {
				content = "";
			}
		} else if (title == null || content == null) {
			throw validation("title and content are required");
		}

		String attachmentName = null;
		ImageSanitizer.Sanitized image = null;
		if (screenshot != null && !screenshot.isEmpty()) {
			image = sanitize(screenshot);
		}

		Feedback feedback = feedbacks.save(new Feedback(request.type(), title, content, owner, request.puzzleId(),
				request.wordId(), request.reason(), deviceInfo(userAgent, request.screen()), clock.instant()));
		if (image != null) {
			attachmentName = store.save(image.bytes(), image.extension());
			deleteFileUnlessCommitted(attachmentName);
			attachments.save(new FeedbackAttachment(feedback.getId(), attachmentName, image.contentType(),
					image.bytes().length));
		}
		return new FeedbackCreated(feedback.getId(), feedback.getType(), feedback.getStatus(),
				owner.type() == OwnerType.MEMBER, attachmentName != null, feedback.getCreatedAt());
	}

	private Word validateWordReport(CreateFeedbackRequest request) {
		if (request.type() != FeedbackType.WORD_ERROR) {
			throw validation("wordId can only be used with the WORD_ERROR type");
		}
		if (request.puzzleId() == null || request.reason() == null) {
			throw validation("puzzleId and reason are required for a word report");
		}
		Puzzle puzzle = puzzles.findWithEntriesById(request.puzzleId())
				.orElseThrow(() -> validation("puzzle not found"));
		return puzzle.getEntries().stream().map(e -> e.getWord()).filter(w -> w.getId().equals(request.wordId()))
				.findFirst().orElseThrow(() -> validation("the word does not belong to this puzzle"));
	}

	private ImageSanitizer.Sanitized sanitize(MultipartFile file) {
		if (file.getSize() > props.maxAttachmentBytes()) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE",
					"the screenshot must be at most " + props.maxAttachmentBytes() / 1024 / 1024 + "MB");
		}
		try {
			return ImageSanitizer.sanitize(file.getBytes(), props.maxImageDimension(), props.maxImagePixels());
		} catch (IOException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT", "the upload could not be read");
		}
	}

	/** 게스트가 이미 회원으로 이전됐다면 새 문의를 받지 않는다(이전 후 주인 없는 문의가 생기는 경쟁 방지). */
	private void requireActiveAccount(Owner owner) {
		if (owner.type() != OwnerType.GUEST) {
			return;
		}
		GuestAccount guest = guests.findForUpdate(UUID.fromString(owner.id()))
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_FOUND", "account no longer exists"));
		if (guest.getMigratedToUserId() != null) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "GUEST_MIGRATED", "this guest has been migrated to a member");
		}
	}

	private static String deviceInfo(String userAgent, String screen) {
		StringBuilder info = new StringBuilder();
		if (userAgent != null && !userAgent.isBlank()) {
			info.append(truncate(ANY_CONTROL.matcher(userAgent).replaceAll(" ").trim(), MAX_USER_AGENT));
		}
		if (screen != null && SCREEN.matcher(screen).matches()) {
			info.append(info.length() > 0 ? " | " : "").append("screen=").append(screen);
		}
		return info.length() == 0 ? null : info.toString();
	}

	/** 앞뒤 공백을 지우고 제어 문자를 거부한다. 비어 있으면 null. 제목은 줄바꿈도 허용하지 않는다. */
	private static String text(String raw, boolean multiline) {
		if (raw == null) {
			return null;
		}
		String value = raw.strip();
		if (value.isEmpty()) {
			return null;
		}
		if ((multiline ? CONTROL : ANY_CONTROL).matcher(value).find()) {
			throw validation("control characters are not allowed");
		}
		return value;
	}

	private static String truncate(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max);
	}

	private static ApiException validation(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
	}

	/** 트랜잭션이 커밋되지 않으면 방금 저장한 파일을 지운다. */
	private void deleteFileUnlessCommitted(String storedName) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					store.delete(storedName);
				}
			}
		});
	}

	// ---------- 내 문의 내역 ----------

	/** 회원 전용(게스트는 401). 최신순이며 답변과 읽음 여부를 포함한다. */
	@Transactional(readOnly = true)
	public MineResponse mine(Owner owner) {
		requireMember(owner);
		List<Feedback> items = feedbacks.findByAuthorTypeAndAuthorIdOrderByCreatedAtDescIdDesc(owner.type(), owner.id());
		if (items.isEmpty()) {
			return new MineResponse(List.of(), 0);
		}
		List<Long> ids = items.stream().map(Feedback::getId).toList();
		Map<Long, List<ReplyView>> repliesByFeedback = new HashMap<>();
		replies.findByFeedbackIdInOrderByCreatedAtAscIdAsc(ids).forEach(r -> repliesByFeedback
				.computeIfAbsent(r.getFeedbackId(), k -> new java.util.ArrayList<>()).add(replyView(r)));
		Set<Long> withAttachment = attachments.findByFeedbackIdIn(ids).stream().map(FeedbackAttachment::getFeedbackId)
				.collect(Collectors.toSet());
		Set<Long> wordIds = items.stream().map(Feedback::getWordId).filter(java.util.Objects::nonNull)
				.collect(Collectors.toCollection(HashSet::new));
		Map<Long, String> korean = words.findAllById(wordIds).stream()
				.collect(Collectors.toMap(Word::getId, Word::getKorean, (a, b) -> a));
		List<FeedbackView> views = items.stream().map(f -> new FeedbackView(f.getId(), f.getType(), f.getTitle(),
				f.getContent(), f.getStatus(), f.getCreatedAt(), f.getPuzzleId(), f.getWordId(),
				f.getWordId() == null ? null : korean.get(f.getWordId()), f.getReason(),
				withAttachment.contains(f.getId()), repliesByFeedback.getOrDefault(f.getId(), List.of()))).toList();
		return new MineResponse(views, replies.countUnread(owner.type(), owner.id()));
	}

	@Transactional(readOnly = true)
	public long unreadCount(Owner owner) {
		requireMember(owner);
		return replies.countUnread(owner.type(), owner.id());
	}

	/** 본인 문의의 답변만 읽음 처리한다. 남의 답변이나 없는 답변은 모두 404다. 읽은 시각은 처음 한 번만 기록된다. */
	@Transactional
	public ReplyView markRead(Owner owner, Long replyId) {
		requireMember(owner);
		replies.findOwned(replyId, owner.type(), owner.id())
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPLY_NOT_FOUND", "reply not found"));
		replies.markReadIfUnread(replyId, clock.instant());
		return replyView(replies.findById(replyId).orElseThrow());
	}

	/** 작성자 본인만 첨부를 받을 수 있다(관리자는 4단계). 본인 것이 아니면 없는 것과 같게 404. */
	@Transactional(readOnly = true)
	public AttachmentFile attachment(Owner owner, Long feedbackId) {
		Feedback feedback = feedbacks.findById(feedbackId).filter(f -> f.isOwnedBy(owner))
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "attachment not found"));
		FeedbackAttachment attachment = attachments.findByFeedbackId(feedback.getId())
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "attachment not found"));
		return new AttachmentFile(store.load(attachment.getStoredName()), attachment.getContentType());
	}

	private static ReplyView replyView(FeedbackReply r) {
		return new ReplyView(r.getId(), r.getContent(), r.getCreatedAt(), r.getReadAt(), r.getReadAt() == null);
	}

	private static void requireMember(Owner owner) {
		if (owner.type() != OwnerType.MEMBER) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "MEMBER_ONLY", "this requires a member account");
		}
	}
}
