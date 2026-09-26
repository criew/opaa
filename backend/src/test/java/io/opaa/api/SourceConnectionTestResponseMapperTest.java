package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.ConfluenceSpaceListRequest;
import io.opaa.api.dto.ConfluenceSpaceListResponse;
import io.opaa.api.dto.ConfluenceSpaceRef;
import io.opaa.api.dto.S3BucketListRequest;
import io.opaa.api.dto.S3BucketListResponse;
import io.opaa.api.dto.S3ScopeCheck;
import io.opaa.api.dto.S3ScopeRef;
import io.opaa.api.dto.S3Settings;
import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.s3.S3ClientFactory;
import io.opaa.indexing.source.s3.S3ConnectionService;
import io.opaa.indexing.source.s3.S3OriginalAccess;
import io.opaa.indexing.source.s3.S3Properties;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3SourceConnector;
import io.opaa.indexing.source.s3.S3SourceSettingsJson;
import io.opaa.indexing.source.s3.events.S3EventService;
import io.opaa.library.SourceBrowseRequest;
import io.opaa.library.SourceConnectionTest;
import io.opaa.security.TargetAddressValidator;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests (no Spring context) - the mapper counterpart of {@code SpaceResponseMapperTest}
 * (#860): pins {@link SourceConnectionTestResponseMapper}'s field-by-field behaviour in both
 * directions, including that a failed probe carries no {@code documentCount}, and how the flat
 * per-type fields become the connector settings of the request's type (ADR-0038).
 */
class SourceConnectionTestResponseMapperTest {

  private final SourceConnectorRegistry connectors = mock(SourceConnectorRegistry.class);

  {
    when(connectors.connector(DocumentSourceType.S3))
        .thenReturn(
            new S3SourceConnector(
                mock(S3ConnectionService.class),
                new S3ClientFactory(S3Properties.defaults(), TargetAddressValidator.disabled()),
                mock(SourceSyncStateRepository.class),
                mock(S3OriginalAccess.class),
                mock(S3EventService.class)));
  }

  @Test
  void toDomainCopiesEveryRequestField() {
    UUID libraryId = UUID.randomUUID();
    SourceConnectionTestRequest request =
        new SourceConnectionTestRequest(DocumentSourceType.CONFLUENCE)
            .sourcePath("/data/documents")
            .sourceUrl(URI.create("https://example.com/documents/"))
            .sourceProxy("proxy.example.com:8080")
            .sourceCredentials("admin:secret")
            .sourceInsecureSsl(true)
            .libraryId(libraryId)
            .confluenceEdition(ConfluenceEdition.CLOUD);

    SourceConnectionTest domain = SourceConnectionTestResponseMapper.toDomain(request, connectors);

    assertThat(domain.sourceType()).isEqualTo(DocumentSourceType.CONFLUENCE);
    assertThat(domain.sourcePath()).isEqualTo("/data/documents");
    assertThat(domain.sourceUrl()).isEqualTo(URI.create("https://example.com/documents/"));
    assertThat(domain.sourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(domain.sourceCredentials()).isEqualTo("admin:secret");
    assertThat(domain.sourceInsecureSsl()).isTrue();
    assertThat(domain.libraryId()).isEqualTo(libraryId);
    assertThat(domain.connectorSettings().asMap()).containsExactly(Map.entry("edition", "CLOUD"));
  }

  @Test
  void anEditionReachesOnlyAConfluenceProbe() {
    SourceConnectionTest domain =
        SourceConnectionTestResponseMapper.toDomain(
            new SourceConnectionTestRequest(DocumentSourceType.HTTP_DIRECTORY)
                .confluenceEdition(ConfluenceEdition.CLOUD),
            connectors);

    assertThat(domain.connectorSettings()).isNull();
  }

  @Test
  void toDomainCarriesTheS3SettingsAndToResponseTheScopeFindings() {
    SourceConnectionTestRequest request =
        new SourceConnectionTestRequest(DocumentSourceType.S3)
            .sourceUrl(URI.create("https://s3.example.org"))
            .sourceCredentials("ak:sk")
            .s3Settings(
                new S3Settings(List.of(new S3ScopeRef("dokumente").prefix("2025")))
                    .region("eu-central-1")
                    .pathStyle(true));

    SourceConnectionTest domain = SourceConnectionTestResponseMapper.toDomain(request, connectors);

    var settings = S3SourceSettingsJson.fromData(domain.connectorSettings());
    assertThat(settings.region()).isEqualTo("eu-central-1");
    assertThat(settings.pathStyle()).isTrue();
    assertThat(settings.scopes()).containsExactly(S3Scope.of("dokumente", "2025/"));
    assertThat(
            SourceConnectionTestResponseMapper.toDomain(
                    new SourceConnectionTestRequest(DocumentSourceType.HTTP_DIRECTORY), connectors)
                .connectorSettings())
        .isNull();

    SourceConnectionTestResponse response =
        SourceConnectionTestResponseMapper.toResponse(
            new SourceConnectionTestResult(
                false,
                "Bereich „dokumente/2025/“: s3:GetObject fehlt",
                null,
                true,
                ConnectorData.of(
                    Map.of(
                        "scopes",
                        List.of(
                            new io.opaa.indexing.source.s3.S3ScopeCheck(
                                    "dokumente",
                                    "2025/",
                                    true,
                                    true,
                                    false,
                                    12,
                                    true,
                                    "s3:GetObject fehlt")
                                .toJson(),
                            new io.opaa.indexing.source.s3.S3ScopeCheck(
                                    "archiv", "", true, true, null, 0, false, null)
                                .toJson())))));

    assertThat(response.getReachable()).isFalse();
    assertThat(response.getCredentialsVerified()).isTrue();
    assertThat(response.getConfluenceEdition()).isNull();
    assertThat(response.getS3Scopes()).hasSize(2);
    S3ScopeCheck first = response.getS3Scopes().get(0);
    assertThat(first.getBucket()).isEqualTo("dokumente");
    assertThat(first.getPrefix()).isEqualTo("2025/");
    assertThat(first.getBucketReachable()).isTrue();
    assertThat(first.getListAllowed()).isTrue();
    assertThat(first.getReadAllowed()).isFalse();
    assertThat(first.getObjectCount()).isEqualTo(12);
    assertThat(first.getObjectCountIsLowerBound()).isTrue();
    assertThat(first.getMessage()).isEqualTo("s3:GetObject fehlt");
    S3ScopeCheck second = response.getS3Scopes().get(1);
    assertThat(second.getReadAllowed()).isNull();
    assertThat(second.getMessage()).isNull();
    assertThat(
            SourceConnectionTestResponseMapper.toResponse(
                    new SourceConnectionTestResult(true, "Verzeichnis erreichbar.", 3L))
                .getS3Scopes())
        .isNull();
  }

  @Test
  void bucketListingMapsEveryRequestFieldAndTheFallback() {
    UUID libraryId = UUID.randomUUID();
    S3BucketListRequest request =
        new S3BucketListRequest(URI.create("https://s3.example.org"))
            .sourceCredentials("ak:sk")
            .sourceProxy("proxy.example.com:8080")
            .sourceInsecureSsl(true)
            .region("eu-central-1")
            .pathStyle(true)
            .libraryId(libraryId);

    SourceBrowseRequest domain = SourceConnectionTestResponseMapper.toDomain(request);

    assertThat(domain.sourceType()).isEqualTo(DocumentSourceType.S3);
    assertThat(domain.sourceUrl()).isEqualTo(URI.create("https://s3.example.org"));
    assertThat(domain.sourceCredentials()).isEqualTo("ak:sk");
    assertThat(domain.sourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(domain.sourceInsecureSsl()).isTrue();
    assertThat(domain.query().asMap())
        .containsExactly(Map.entry("region", "eu-central-1"), Map.entry("pathStyle", true));
    assertThat(domain.libraryId()).isEqualTo(libraryId);

    S3BucketListResponse listed =
        SourceConnectionTestResponseMapper.toResponse(
            new SourceListing(
                true,
                List.of(
                    new SourceListing.Entry("dokumente", null),
                    new SourceListing.Entry("archiv", null)),
                null));
    assertThat(listed.getListingPermitted()).isTrue();
    assertThat(listed.getBuckets()).containsExactly("dokumente", "archiv");
    assertThat(listed.getMessage()).isNull();

    S3BucketListResponse fallback =
        SourceConnectionTestResponseMapper.toResponse(
            new SourceListing(false, List.of(), "nicht lesbar"));
    assertThat(fallback.getListingPermitted()).isFalse();
    assertThat(fallback.getBuckets()).isEmpty();
    assertThat(fallback.getMessage()).isEqualTo("nicht lesbar");
  }

  @Test
  void toResponseCarriesTheConfluenceFieldsAndLeavesThemNullOtherwise() {
    SourceConnectionTestResponse confluence =
        SourceConnectionTestResponseMapper.toResponse(
            new SourceConnectionTestResult(
                true,
                "Zugangsdaten gültig.",
                null,
                true,
                ConnectorData.of(Map.of("edition", "DATA_CENTER"))));
    assertThat(confluence.getConfluenceEdition()).isEqualTo(ConfluenceEdition.DATA_CENTER);
    assertThat(confluence.getCredentialsVerified()).isTrue();
    assertThat(confluence.getDocumentCount()).isNull();

    SourceConnectionTestResponse plain =
        SourceConnectionTestResponseMapper.toResponse(
            new SourceConnectionTestResult(true, "Verzeichnis erreichbar.", 3L));
    assertThat(plain.getConfluenceEdition()).isNull();
    assertThat(plain.getCredentialsVerified()).isNull();
  }

  @Test
  void spaceListingMapsEveryRequestFieldAndEverySpace() {
    UUID libraryId = UUID.randomUUID();
    ConfluenceSpaceListRequest request =
        new ConfluenceSpaceListRequest(
                URI.create("https://wiki.example.org"), ConfluenceEdition.DATA_CENTER)
            .sourceCredentials("pat")
            .sourceProxy("proxy.example.com:8080")
            .sourceInsecureSsl(true)
            .libraryId(libraryId);

    SourceBrowseRequest listing = SourceConnectionTestResponseMapper.toDomain(request);

    assertThat(listing.sourceType()).isEqualTo(DocumentSourceType.CONFLUENCE);
    assertThat(listing.sourceUrl()).isEqualTo(URI.create("https://wiki.example.org"));
    assertThat(listing.query().asMap()).containsExactly(Map.entry("edition", "DATA_CENTER"));
    assertThat(listing.sourceCredentials()).isEqualTo("pat");
    assertThat(listing.sourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(listing.sourceInsecureSsl()).isTrue();
    assertThat(listing.libraryId()).isEqualTo(libraryId);

    ConfluenceSpaceListResponse response =
        SourceConnectionTestResponseMapper.toResponse(
            SourceConnectionTestResponseMapper.toRefs(
                new SourceListing(
                    true,
                    List.of(
                        new SourceListing.Entry("ENG", "Engineering"),
                        new SourceListing.Entry("HR", null)),
                    null)));
    assertThat(response.getSpaces())
        .extracting(ConfluenceSpaceRef::getKey, ConfluenceSpaceRef::getName)
        .containsExactly(tuple("ENG", "Engineering"), tuple("HR", null));
  }

  @Test
  void toResponseCarriesTheDocumentCountForAReachableSource() {
    SourceConnectionTestResult result =
        new SourceConnectionTestResult(true, "Verzeichnis erreichbar, 3 Dokumente gefunden.", 3L);

    SourceConnectionTestResponse response = SourceConnectionTestResponseMapper.toResponse(result);

    assertThat(response.getReachable()).isTrue();
    assertThat(response.getMessage()).isEqualTo("Verzeichnis erreichbar, 3 Dokumente gefunden.");
    assertThat(response.getDocumentCount()).isEqualTo(3L);
  }

  @Test
  void toResponseLeavesDocumentCountNullForAnUnreachableSource() {
    SourceConnectionTestResult result =
        new SourceConnectionTestResult(false, "Das Verzeichnis existiert nicht.", null);

    SourceConnectionTestResponse response = SourceConnectionTestResponseMapper.toResponse(result);

    assertThat(response.getReachable()).isFalse();
    assertThat(response.getDocumentCount()).isNull();
  }
}
