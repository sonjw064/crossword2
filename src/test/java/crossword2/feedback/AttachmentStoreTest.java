package crossword2.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AttachmentStoreTest {

	@TempDir
	Path temp;

	private AttachmentStore store(Path dir) {
		return new AttachmentStore(new FeedbackProperties(dir.toString(), 2_097_152, 4096, 12_000_000,
				Duration.ofDays(90), 5, 10));
	}

	@Test
	void savesUnderARandomNameInsideTheUploadFolder() throws Exception {
		Path dir = temp.resolve("uploads");
		AttachmentStore store = store(dir);

		String first = store.save(new byte[] { 1, 2, 3 }, "png");
		String second = store.save(new byte[] { 4 }, "png");

		assertThat(first).matches("[0-9a-f-]{36}\\.png").isNotEqualTo(second);
		assertThat(Files.readAllBytes(dir.resolve(first))).containsExactly(1, 2, 3);
		assertThat(store.load(first)).containsExactly(1, 2, 3);
		assertThat(store.exists(first)).isTrue();
	}

	@Test
	void createsTheFolderAndLeavesNoTempFiles() throws Exception {
		Path dir = temp.resolve("nested/uploads");
		AttachmentStore store = store(dir);

		store.save(new byte[] { 1 }, "jpg");

		try (Stream<Path> files = Files.list(dir)) {
			assertThat(files.map(p -> p.getFileName().toString())).allMatch(n -> n.endsWith(".jpg"));
		}
	}

	@Test
	void deleteRemovesTheFileAndIsQuietWhenItIsGone() {
		AttachmentStore store = store(temp);
		String name = store.save(new byte[] { 1 }, "png");

		store.delete(name);
		store.delete(name);

		assertThat(store.exists(name)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "../secret.png", "..\\secret.png", "/etc/passwd", "a/b.png", "evil.png", "",
			"00000000-0000-0000-0000-000000000000.exe", "00000000-0000-0000-0000-000000000000.png/../x",
			"00000000-0000-0000-0000-000000000000.html" })
	void refusesAnyNameThatIsNotOneItGenerated(String name) {
		AttachmentStore store = store(temp);

		assertThatThrownBy(() -> store.load(name)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.delete(name)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void doesNotTouchFilesOutsideTheUploadFolder() throws Exception {
		Path outside = temp.resolve("outside.png");
		Files.write(outside, new byte[] { 9 });
		AttachmentStore store = store(temp.resolve("uploads"));

		assertThatThrownBy(() -> store.delete("../outside.png")).isInstanceOf(IllegalArgumentException.class);

		assertThat(Files.exists(outside)).isTrue();
	}
}
