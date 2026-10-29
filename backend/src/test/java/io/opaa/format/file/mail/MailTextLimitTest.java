package io.opaa.format.file.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.chunk.ChunkingService;
import io.opaa.format.shared.ParserLimitExceededException;
import io.opaa.format.shared.ParserLimitProperties;
import io.opaa.indexing.IndexingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A mail's Kopfdaten and body count against the shared text limit before they are chunked: past its
 * chunk count the token splitter would append the whole rest of the text to its last chunk.
 */
class MailTextLimitTest {

  @TempDir Path tempDir;

  /** Regression guard: a body past the text limit became chunks instead of failing. */
  @Test
  void aBodyPastTheTextLimitFails() throws IOException {
    Path file = eml("lang.eml", "Ein Satz im Text der Nachricht. ".repeat(200));

    assertThatThrownBy(() -> format(2_000).run(DocumentFormatSource.ofFile(file, "lang.eml")))
        .isInstanceOf(ParserLimitExceededException.class)
        .hasMessageContaining("Mail message exceeds");
  }

  @Test
  void aBodyWithinTheTextLimitIsChunked() throws IOException {
    Path file = eml("kurz.eml", "Ein Satz im Text der Nachricht. ".repeat(20));

    DocumentFormatResult result = format(2_000).run(DocumentFormatSource.ofFile(file, "kurz.eml"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
  }

  private static MailDocumentFormat format(long maxTextCharacters) {
    return new MailDocumentFormat(
        new ChunkingService(new IndexingProperties(1000, 100, 50, null, null, null, 1)),
        new MailProperties(0, 0, 0),
        Clock.systemUTC(),
        new ParserLimitProperties(maxTextCharacters, 0, 0, 0, 0, 0, 0, 0, 0));
  }

  private Path eml(String name, String body) throws IOException {
    Path file = tempDir.resolve(name);
    Files.writeString(
        file,
        "From: a@example.org\r\nTo: b@example.org\r\nSubject: Test\r\nMIME-Version: 1.0\r\n"
            + "Content-Type: text/plain; charset=utf-8\r\n\r\n"
            + body
            + "\r\n");
    return file;
  }
}
