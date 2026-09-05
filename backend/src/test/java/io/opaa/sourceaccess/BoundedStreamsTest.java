package io.opaa.sourceaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The one byte ceiling behind every bounded download, attachment extraction and archive entry: a
 * stream exactly at the limit passes, the first byte past it throws, and nothing past it is ever
 * handed on - whichever of the four shapes a caller uses.
 */
class BoundedStreamsTest {

  private static byte[] bytes(int count) {
    return "x".repeat(count).getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void inputPassesAStreamExactlyAtTheLimit() throws IOException {
    try (InputStream in = BoundedStreams.input(new ByteArrayInputStream(bytes(10)), 10)) {
      assertThat(in.readAllBytes()).hasSize(10);
    }
  }

  @Test
  void inputThrowsTheMomentAReadCrossesTheLimit() throws IOException {
    try (InputStream in = BoundedStreams.input(new ByteArrayInputStream(bytes(11)), 10)) {
      assertThatThrownBy(in::readAllBytes)
          .isInstanceOf(BoundedStreams.LimitExceededException.class)
          .hasMessageContaining("size limit")
          .satisfies(
              e ->
                  assertThat(((BoundedStreams.LimitExceededException) e).maxBytes()).isEqualTo(10));
    }
  }

  @Test
  void inputCountsSingleByteReadsToo() throws IOException {
    try (InputStream in = BoundedStreams.input(new ByteArrayInputStream(bytes(3)), 2)) {
      assertThat(in.read()).isNotEqualTo(-1);
      assertThat(in.read()).isNotEqualTo(-1);
      assertThatThrownBy(in::read).isInstanceOf(BoundedStreams.LimitExceededException.class);
    }
  }

  @Test
  void outputThrowsBeforeTheExcessIsWritten() throws IOException {
    ByteArrayOutputStream target = new ByteArrayOutputStream();
    OutputStream out = BoundedStreams.output(target, 10);

    out.write(bytes(10));
    assertThatThrownBy(() -> out.write(bytes(1)))
        .isInstanceOf(BoundedStreams.LimitExceededException.class);
    assertThatThrownBy(() -> out.write('x'))
        .isInstanceOf(BoundedStreams.LimitExceededException.class);

    assertThat(target.size()).as("nothing past the limit reaches the target").isEqualTo(10);
  }

  @Test
  void copyWritesNothingPastTheLimit() throws IOException {
    ByteArrayOutputStream target = new ByteArrayOutputStream();

    assertThatThrownBy(
            () -> BoundedStreams.copy(new ByteArrayInputStream(bytes(20_000)), target, 8_192))
        .isInstanceOf(BoundedStreams.LimitExceededException.class);

    assertThat(target.size()).isLessThanOrEqualTo(8_192);
  }

  @Test
  void copyTransfersAStreamWithinTheLimitCompletely() throws IOException {
    ByteArrayOutputStream target = new ByteArrayOutputStream();

    BoundedStreams.copy(new ByteArrayInputStream(bytes(5_000)), target, 5_000);

    assertThat(target.size()).isEqualTo(5_000);
  }

  @Test
  void readFullyReturnsTheWholeBodyUpToTheLimitAndThrowsBeyondIt() throws IOException {
    assertThat(BoundedStreams.readFully(new ByteArrayInputStream(bytes(10)), 10)).hasSize(10);
    assertThatThrownBy(() -> BoundedStreams.readFully(new ByteArrayInputStream(bytes(11)), 10))
        .isInstanceOf(BoundedStreams.LimitExceededException.class);
  }
}
