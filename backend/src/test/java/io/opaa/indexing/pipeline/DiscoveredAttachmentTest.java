package io.opaa.indexing.pipeline;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DiscoveredAttachmentTest {

  @Test
  void aNullTempFileIsRejectedAtConstructionRatherThanNpeingLaterInCleanup() {
    // #1181 review, finding 1: cleanup must never be the place a malformed attachment surfaces -
    // by the time DocumentPipelineRunner#run iterates discoveredAttachments, an invalid one would
    // otherwise NPE inside a finally and turn an already-successful result into a failure.
    assertThatThrownBy(() -> new DiscoveredAttachment("a.pdf", null, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aBlankFileNameIsRejected() {
    assertThatThrownBy(() -> new DiscoveredAttachment(" ", Path.of("a.pdf"), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
