package io.opaa.indexing.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.EmbeddingReindexStatusResponse;
import io.opaa.indexing.chunk.EmbeddingIdentity;
import io.opaa.indexing.chunk.EmbeddingIndexRecord;
import io.opaa.indexing.maintenance.EmbeddingReindexProgress;
import org.junit.jupiter.api.Test;

/** The index identities leave without the secrets an address may carry. */
class EmbeddingReindexResponseMapperTest {

  @Test
  void everyIndexIdentityLeavesWithoutQueryFragmentAndCredentials() {
    EmbeddingIdentity current =
        new EmbeddingIdentity("bge-m3", "https://llm.example.invalid/v1?api-key=geheim", 1024, 2);
    EmbeddingIdentity configured =
        new EmbeddingIdentity(
            "bge-m3-neu", "https://nutzer:geheim@llm.example.invalid/v1", 1024, 3);
    EmbeddingIdentity previous =
        new EmbeddingIdentity("alt", "https://llm.example.invalid/v1#geheim", 768, 1);

    EmbeddingReindexStatusResponse response =
        EmbeddingReindexResponseMapper.toResponse(
            new EmbeddingReindexProgress(
                current,
                configured,
                true,
                false,
                EmbeddingIndexRecord.Run.NONE,
                0,
                0,
                true,
                previous,
                false,
                0));

    assertThat(response.getCurrent().getBaseUrl()).isEqualTo("https://llm.example.invalid/v1");
    assertThat(response.getConfigured().getBaseUrl()).isEqualTo("https://llm.example.invalid/v1");
    assertThat(response.getPrevious().getBaseUrl()).isEqualTo("https://llm.example.invalid/v1");
  }
}
