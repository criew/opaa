package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
import io.opaa.indexing.source.confluence.ConfluenceSpace;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.library.ConfluenceSpaceListing;
import io.opaa.library.S3BucketListResult;
import io.opaa.library.S3BucketListingRequest;
import io.opaa.library.SourceConnectionTest;
import io.opaa.library.SourceConnectionTestResult;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests (no Spring context) - the mapper counterpart of {@code SpaceResponseMapperTest}
 * (#860): pins {@link SourceConnectionTestResponseMapper}'s field-by-field behaviour in both
 * directions, including that a failed probe carries no {@code documentCount}.
 */
class SourceConnectionTestResponseMapperTest {

  @Test
  void toDomainCopiesEveryRequestField() {
    UUID libraryId = UUID.randomUUID();
    SourceConnectionTestRequest request =
        new SourceConnectionTestRequest(DocumentSourceType.HTTP_DIRECTORY)
            .sourcePath("/data/documents")
            .sourceUrl(URI.create("https://example.com/documents/"))
            .sourceProxy("proxy.example.com:8080")
            .sourceCredentials("admin:secret")
            .sourceInsecureSsl(true)
            .libraryId(libraryId)
            .confluenceEdition(ConfluenceEdition.CLOUD);

    SourceConnectionTest domain = SourceConnectionTestResponseMapper.toDomain(request);

    assertThat(domain.sourceType()).isEqualTo(DocumentSourceType.HTTP_DIRECTORY);
    assertThat(domain.sourcePath()).isEqualTo("/data/documents");
    assertThat(domain.sourceUrl()).isEqualTo(URI.create("https://example.com/documents/"));
    assertThat(domain.sourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(domain.sourceCredentials()).isEqualTo("admin:secret");
    assertThat(domain.sourceInsecureSsl()).isTrue();
    assertThat(domain.libraryId()).isEqualTo(libraryId);
    assertThat(domain.confluenceEdition()).isEqualTo(ConfluenceEdition.CLOUD);
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

    SourceConnectionTest domain = SourceConnectionTestResponseMapper.toDomain(request);

    assertThat(domain.s3Settings().region()).isEqualTo("eu-central-1");
    assertThat(domain.s3Settings().pathStyle()).isTrue();
    assertThat(domain.s3Settings().scopes()).containsExactly(S3Scope.of("dokumente", "2025/"));
    assertThat(
            SourceConnectionTestResponseMapper.toDomain(
                    new SourceConnectionTestRequest(DocumentSourceType.HTTP_DIRECTORY))
                .s3Settings())
        .isNull();

    SourceConnectionTestResponse response =
        SourceConnectionTestResponseMapper.toResponse(
            new SourceConnectionTestResult(
                false,
                "Bereich „dokumente/2025/“: s3:GetObject fehlt",
                null,
                null,
                true,
                List.of(
                    new io.opaa.library.S3ScopeCheck(
                        "dokumente", "2025/", true, true, false, 12, true, "s3:GetObject fehlt"),
                    new io.opaa.library.S3ScopeCheck(
                        "archiv", "", true, true, null, 0, false, null))));

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

    S3BucketListingRequest domain = SourceConnectionTestResponseMapper.toDomain(request);

    assertThat(domain.sourceUrl()).isEqualTo(URI.create("https://s3.example.org"));
    assertThat(domain.sourceCredentials()).isEqualTo("ak:sk");
    assertThat(domain.sourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(domain.sourceInsecureSsl()).isTrue();
    assertThat(domain.region()).isEqualTo("eu-central-1");
    assertThat(domain.pathStyle()).isTrue();
    assertThat(domain.libraryId()).isEqualTo(libraryId);

    S3BucketListResponse listed =
        SourceConnectionTestResponseMapper.toResponse(
            new S3BucketListResult(true, List.of("dokumente", "archiv"), null));
    assertThat(listed.getListingPermitted()).isTrue();
    assertThat(listed.getBuckets()).containsExactly("dokumente", "archiv");
    assertThat(listed.getMessage()).isNull();

    S3BucketListResponse fallback =
        SourceConnectionTestResponseMapper.toResponse(
            new S3BucketListResult(false, List.of(), "nicht lesbar"));
    assertThat(fallback.getListingPermitted()).isFalse();
    assertThat(fallback.getBuckets()).isEmpty();
    assertThat(fallback.getMessage()).isEqualTo("nicht lesbar");
  }

  @Test
  void toResponseCarriesTheConfluenceFieldsAndLeavesThemNullOtherwise() {
    SourceConnectionTestResponse confluence =
        SourceConnectionTestResponseMapper.toResponse(
            new SourceConnectionTestResult(
                true, "Zugangsdaten gültig.", null, ConfluenceEdition.DATA_CENTER, true));
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

    ConfluenceSpaceListing listing = SourceConnectionTestResponseMapper.toDomain(request);

    assertThat(listing.sourceUrl()).isEqualTo(URI.create("https://wiki.example.org"));
    assertThat(listing.confluenceEdition()).isEqualTo(ConfluenceEdition.DATA_CENTER);
    assertThat(listing.sourceCredentials()).isEqualTo("pat");
    assertThat(listing.sourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(listing.sourceInsecureSsl()).isTrue();
    assertThat(listing.libraryId()).isEqualTo(libraryId);

    ConfluenceSpaceListResponse response =
        SourceConnectionTestResponseMapper.toResponse(
            SourceConnectionTestResponseMapper.toRefs(
                List.of(
                    new ConfluenceSpace("1", "ENG", "Engineering"),
                    new ConfluenceSpace("2", "HR", null))));
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
