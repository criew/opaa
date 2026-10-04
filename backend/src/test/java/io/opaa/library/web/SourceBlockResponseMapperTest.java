package io.opaa.library.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.SourceBlockReason;
import io.opaa.indexing.source.SourceBlock;
import org.junit.jupiter.api.Test;

/** Every reason of the core is a reason of the API, with who is in charge and the notice. */
class SourceBlockResponseMapperTest {

  @Test
  void everyReasonMapsWithResponsibleAndNotice() {
    for (SourceBlock.Reason reason : SourceBlock.Reason.values()) {
      io.opaa.api.dto.SourceBlock response =
          SourceBlockResponseMapper.toResponse(new SourceBlock(reason, "Zuständig", "Hinweis"));

      assertThat(response.getReason()).isEqualTo(SourceBlockReason.valueOf(reason.name()));
      assertThat(response.getResponsible()).isEqualTo("Zuständig");
      assertThat(response.getNotice()).isEqualTo("Hinweis");
    }
    assertThat(SourceBlockReason.values()).hasSameSizeAs(SourceBlock.Reason.values());
    assertThat(SourceBlockResponseMapper.toResponse(null)).isNull();
  }
}
