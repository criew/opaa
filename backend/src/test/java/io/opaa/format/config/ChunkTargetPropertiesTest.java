package io.opaa.format.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.chunk.ChunkSizing;
import io.opaa.format.file.markdown.MarkdownDocumentFormat;
import io.opaa.format.shared.HeadingSectionSplitter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code opaa.indexing.chunk-target-characters} reaches every format on the shared cut, defaults to
 * 4,000 characters and is rejected outside its range at startup.
 */
class ChunkTargetPropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(Collaborators.class, FormatConfiguration.class);

  @TempDir Path tempDir;

  @Test
  void defaultsToFourThousandCharactersWhenUnset() {
    contextRunner.run(
        context ->
            assertThat(context.getBean(HeadingSectionSplitter.class).softChunkCharLimit())
                .isEqualTo(HeadingSectionSplitter.DEFAULT_SOFT_CHUNK_CHAR_LIMIT));
  }

  @Test
  void theConfiguredTargetReachesTheFormats() throws IOException {
    Path file = tempDir.resolve("satzung.md");
    Files.writeString(file, "# Satzung\n\n" + sentences(9_000));
    DocumentFormatSource source = DocumentFormatSource.ofFile(file, "satzung.md", ".md");

    contextRunner
        .withPropertyValues("opaa.indexing.chunk-target-characters=2000")
        .run(
            context -> {
              assertThat(context.getBean(HeadingSectionSplitter.class).softChunkCharLimit())
                  .isEqualTo(2_000);
              DocumentFormatResult result =
                  context.getBean(MarkdownDocumentFormat.class).run(source);
              assertThat(result.chunks()).hasSizeGreaterThanOrEqualTo(5);
              assertThat(result.chunks())
                  .allSatisfy(
                      chunk ->
                          assertThat(chunk.getText().length())
                              .isLessThanOrEqualTo(2_000 + "Satzung\n\n".length()));
            });
  }

  @Test
  void aTargetOutsideTheRangeFailsTheStartup() {
    contextRunner
        .withPropertyValues("opaa.indexing.chunk-target-characters=400")
        .run(context -> assertThat(context).hasFailed());
    contextRunner
        .withPropertyValues("opaa.indexing.chunk-target-characters=8001")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void zeroMeansTheDefault() {
    assertThat(new ChunkTargetProperties(0).chunkTargetCharacters())
        .isEqualTo(HeadingSectionSplitter.DEFAULT_SOFT_CHUNK_CHAR_LIMIT);
  }

  private static String sentences(int length) {
    StringBuilder text = new StringBuilder();
    for (int i = 1; text.length() < length; i++) {
      text.append("Die Gebühr Nummer ")
          .append(i)
          .append(" wird mit der Bekanntgabe des Bescheids fällig. ");
    }
    return text.toString().strip();
  }

  /** What {@link FormatConfiguration} expects from the application around it. */
  @Configuration(proxyBeanMethods = false)
  static class Collaborators {

    @Bean
    ChunkSizing chunkSizing() {
      return new ChunkSizing() {
        @Override
        public int chunkSize() {
          return 1_000;
        }

        @Override
        public int chunkOverlap() {
          return 100;
        }
      };
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
