package crossword2.feedback;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 첨부 파일 저장소. 웹 루트 밖의 폴더에 서버가 만든 임의 이름으로만 저장하고, 사용자가 준 이름은 쓰지 않는다.
 * 이 폴더는 정적으로 서빙되지 않으며 파일은 컨트롤러(작성자 확인)를 통해서만 내려간다.
 */
@Component
public class AttachmentStore {

	private static final Pattern STORED_NAME = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg)$");

	private final Path root;

	public AttachmentStore(FeedbackProperties props) {
		this.root = Path.of(props.uploadDir()).toAbsolutePath().normalize();
	}

	/** @return 저장된 파일 이름 */
	public String save(byte[] bytes, String extension) {
		String name = UUID.randomUUID() + "." + extension;
		try {
			Files.createDirectories(root);
			Path target = resolve(name);
			Path temp = Files.createTempFile(root, "upload-", ".tmp");
			try {
				Files.write(temp, bytes);
				Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
			} finally {
				Files.deleteIfExists(temp);
			}
			return name;
		} catch (IOException e) {
			throw new UncheckedIOException("could not store the attachment", e);
		}
	}

	public byte[] load(String storedName) {
		try {
			return Files.readAllBytes(resolve(storedName));
		} catch (IOException e) {
			throw new UncheckedIOException("could not read the attachment", e);
		}
	}

	/** 없는 파일이어도 조용히 넘어간다. */
	public void delete(String storedName) {
		try {
			Files.deleteIfExists(resolve(storedName));
		} catch (IOException e) {
			throw new UncheckedIOException("could not delete the attachment", e);
		}
	}

	boolean exists(String storedName) {
		return Files.exists(resolve(storedName));
	}

	/** 저장된 이름 형식만 허용하고, 결과 경로가 저장 폴더 안인지 한 번 더 확인한다(경로 조작 방지). */
	private Path resolve(String storedName) {
		if (storedName == null || !STORED_NAME.matcher(storedName).matches()) {
			throw new IllegalArgumentException("invalid stored name");
		}
		Path path = root.resolve(storedName).normalize();
		if (!path.startsWith(root)) {
			throw new IllegalArgumentException("invalid stored name");
		}
		return path;
	}
}
