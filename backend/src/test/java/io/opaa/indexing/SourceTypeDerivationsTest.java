package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DocumentSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every place that used to enumerate "remote or local" now derives it from {@link
 * DocumentSourceType#isRemote()} - the deep link, the re-index access decision and the SQL literal
 * list of the stale-document selection.
 */
class SourceTypeDerivationsTest {

  @ParameterizedTest
  @EnumSource(DocumentSourceType.class)
  void theDeepLinkIsTheFilePathForARemoteTypeAndAbsentForALocalOne(DocumentSourceType type) {
    Document document =
        new Document("bericht.pdf", "https://quelle.example/bericht.pdf", "application/pdf", 12L);
    document.setSourceType(type);

    assertThat(document.getDeepLinkSourceUrl())
        .isEqualTo(type.isRemote() ? "https://quelle.example/bericht.pdf" : null);
    assertThat(StoredDocumentSourceAccess.isRemote(document)).isEqualTo(type.isRemote());
  }

  @Test
  void theStaleSelectionNamesEveryLocalTypeAsASqlLiteralList() {
    assertThat(PipelineReindexService.localSourceTypeSqlList()).isEqualTo("'FILESYSTEM', 'UPLOAD'");
  }
}
