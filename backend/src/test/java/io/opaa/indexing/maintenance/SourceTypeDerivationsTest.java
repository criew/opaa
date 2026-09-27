package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.document.StoredDocumentSourceAccess;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceConnectorStubs;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.SourceType;
import io.opaa.test.SourceTypes;
import org.junit.jupiter.api.Test;

/**
 * Every place that decides "remote or local" derives it from the connector's descriptor - the deep
 * link, the re-index access decision and the SQL literal list of the stale-document selection.
 */
class SourceTypeDerivationsTest {

  private final SourceConnectorRegistry connectors = SourceConnectorStubs.registry();
  private final StoredDocumentSourceAccess sourceAccess =
      new StoredDocumentSourceAccess(null, null, null, null, null, null, connectors);

  @Test
  void theDeepLinkIsTheFilePathForALinkableRemoteTypeAndAbsentOtherwise() {
    assertThat(connectors.deepLink(document(SourceTypes.CONFLUENCE))).isEqualTo(ADDRESS);
    assertThat(connectors.deepLink(document(SourceTypes.HTTP_DIRECTORY))).isEqualTo(ADDRESS);
    assertThat(connectors.deepLink(document(SourceTypes.RSS_FEED))).isEqualTo(ADDRESS);
    assertThat(connectors.deepLink(document(SourceTypes.FILESYSTEM))).isNull();
    assertThat(connectors.deepLink(document(SourceType.UPLOAD))).isNull();
    assertThat(sourceAccess.isRemote(document(SourceTypes.CONFLUENCE))).isTrue();
    assertThat(sourceAccess.isRemote(document(SourceTypes.FILESYSTEM))).isFalse();
    assertThat(sourceAccess.isRemote(document(SourceType.UPLOAD))).isFalse();
  }

  @Test
  void anS3IdentityIsRemoteButNoDeepLink() {
    Document document =
        new Document(
            "sitzung.pdf",
            "s3://protokolle/2025/sitzung.pdf",
            "application/pdf",
            12L,
            SourceTypes.S3);

    assertThat(connectors.deepLink(document)).isNull();
    assertThat(sourceAccess.isRemote(document)).isTrue();
  }

  @Test
  void aTypeNoConnectorServesIsRemoteSoNothingLocalIsReadInItsName() {
    Document document = document(SourceType.of("RETIRED"));

    assertThat(sourceAccess.isRemote(document)).isTrue();
    assertThat(connectors.deepLink(document)).isNull();
  }

  @Test
  void theStaleSelectionNamesEveryLocalTypeAsASqlLiteralList() {
    PipelineReindexService service =
        new PipelineReindexService(null, null, null, null, null, null, sourceAccess, "public", "v");

    assertThat(service.localSourceTypeSqlList()).isEqualTo("'FILESYSTEM', 'UPLOAD'");
  }

  private static final String ADDRESS = "https://quelle.example/bericht.pdf";

  private static Document document(SourceType type) {
    Document document = new Document("bericht.pdf", ADDRESS, "application/pdf", 12L, type);
    return document;
  }
}
