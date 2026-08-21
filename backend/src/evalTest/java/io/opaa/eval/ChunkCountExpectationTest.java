package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.eval.ChunkCountExpectation.DocumentChunkCount;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkCountExpectationTest {

  @Test
  void oneChunkExpectationHoldsWhenEveryDocumentHasExactlyOneChunk() {
    var expectation = ChunkCountExpectation.exactlyOneChunk();
    var documents = List.of(new DocumentChunkCount("a.md", 1), new DocumentChunkCount("b.md", 1));

    assertThat(expectation.violations(documents)).isEmpty();
  }

  @Test
  void oneChunkExpectationAbortsOnADocumentWithMoreThanOneChunk() {
    var expectation = ChunkCountExpectation.exactlyOneChunk();
    var documents = List.of(new DocumentChunkCount("a.md", 1), new DocumentChunkCount("b.md", 3));

    var violations = expectation.violations(documents);

    assertThat(violations).hasSize(1);
    assertThat(violations.get(0).fileName()).isEqualTo("b.md");
    assertThat(violations.get(0).chunkCount()).isEqualTo(3);
  }

  @Test
  void oneChunkExpectationAbortsOnADocumentWithZeroChunks() {
    var expectation = ChunkCountExpectation.exactlyOneChunk();
    var documents = List.of(new DocumentChunkCount("empty.md", 0));

    assertThat(expectation.violations(documents)).hasSize(1);
  }

  @Test
  void minChunksExpectationHoldsWhenEveryDocumentClearsTheMinimum() {
    var expectation = ChunkCountExpectation.atLeast(3);
    var documents = List.of(new DocumentChunkCount("a.md", 3), new DocumentChunkCount("b.md", 7));

    assertThat(expectation.violations(documents)).isEmpty();
  }

  @Test
  void minChunksExpectationAbortsOnADocumentBelowTheMinimum() {
    var expectation = ChunkCountExpectation.atLeast(3);
    var documents =
        List.of(new DocumentChunkCount("a.md", 3), new DocumentChunkCount("short.md", 1));

    var violations = expectation.violations(documents);

    assertThat(violations).hasSize(1);
    assertThat(violations.get(0).fileName()).isEqualTo("short.md");
    assertThat(violations.get(0).reason()).contains("at least 3");
  }

  @Test
  void minChunksExpectationRejectsAThresholdBelowTwo() {
    assertThatThrownBy(() -> ChunkCountExpectation.atLeast(1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void violationsAreSortedByFileName() {
    var expectation = ChunkCountExpectation.exactlyOneChunk();
    var documents = List.of(new DocumentChunkCount("z.md", 2), new DocumentChunkCount("a.md", 2));

    var violations = expectation.violations(documents);

    assertThat(violations)
        .extracting(ChunkCountExpectation.Violation::fileName)
        .containsExactly("a.md", "z.md");
  }
}
