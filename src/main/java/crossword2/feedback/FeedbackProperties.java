package crossword2.feedback;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 문의 설정. 업로드 폴더는 웹 루트 밖이어야 하며(정적 서빙 금지) 운영에서는 환경변수 UPLOAD_DIR로 지정한다.
 */
@ConfigurationProperties("app.feedback")
public record FeedbackProperties(
		@DefaultValue("./data/uploads") String uploadDir,
		@DefaultValue("2097152") long maxAttachmentBytes,
		@DefaultValue("4096") int maxImageDimension,
		@DefaultValue("12000000") long maxImagePixels,
		@DefaultValue("90d") Duration guestRetention,
		@DefaultValue("5") int perOwnerPerHour,
		@DefaultValue("10") int perIpPerHour) {
}
