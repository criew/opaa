package io.opaa.library.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.opaa.api.dto.SourceBrowseEntry;
import io.opaa.api.dto.SourceBrowseRequest;
import io.opaa.api.dto.SourceBrowseResponse;
import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.api.dto.SourceTypeDescriptor;
import io.opaa.common.ValidationException;
import io.opaa.connection.ConnectorReleaseService.TypeCreation;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.PushIntake;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceListing;
import io.opaa.library.SourceConnectionTest;
import io.opaa.test.SourceTypes;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests (no Spring context) - the mapper counterpart of {@code SpaceResponseMapperTest}
 * (#860): pins {@link SourceConnectionTestResponseMapper}'s field-by-field behaviour, including
 * that a failed probe carries no {@code documentCount} and that connector settings and findings
 * travel as opaque objects (ADR-0038).
 */
class SourceConnectionTestResponseMapperTest {

  @Test
  void toDomainCopiesEveryRequestField() {
    UUID libraryId = UUID.randomUUID();
    SourceConnectionTestRequest request =
        new SourceConnectionTestRequest("CONFLUENCE")
            .sourcePath("/data/documents")
            .sourceUrl(URI.create("https://example.com/documents/"))
            .sourceProxy("proxy.example.com:8080")
            .sourceCredentials("admin:secret")
            .sourceInsecureSsl(true)
            .libraryId(libraryId)
            .sourceSettings(Map.of("edition", "CLOUD"));

    SourceConnectionTest domain = SourceConnectionTestResponseMapper.toDomain(request);

    assertThat(domain.sourceType()).isEqualTo(SourceTypes.CONFLUENCE);
    assertThat(domain.sourcePath()).isEqualTo("/data/documents");
    assertThat(domain.sourceUrl()).isEqualTo(URI.create("https://example.com/documents/"));
    assertThat(domain.sourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(domain.sourceCredentials()).isEqualTo("admin:secret");
    assertThat(domain.sourceInsecureSsl()).isTrue();
    assertThat(domain.libraryId()).isEqualTo(libraryId);
    assertThat(domain.connectorSettings().asMap()).containsExactly(Map.entry("edition", "CLOUD"));
    assertThat(
            SourceConnectionTestResponseMapper.toDomain(new SourceConnectionTestRequest("S3"))
                .connectorSettings())
        .isNull();
  }

  @Test
  void theFindingsTravelAsTheDetailsObject() {
    SourceConnectionTestResponse response =
        SourceConnectionTestResponseMapper.toResponse(
            new SourceConnectionTestResult(
                false,
                "Bereich „dokumente/2025/“: s3:GetObject fehlt",
                null,
                true,
                ConnectorData.of(Map.of("scopes", List.of(Map.of("bucket", "dokumente"))))));

    assertThat(response.getReachable()).isFalse();
    assertThat(response.getCredentialsVerified()).isTrue();
    assertThat(response.getDetails()).containsKey("scopes");
    assertThat(
            SourceConnectionTestResponseMapper.toResponse(
                    new SourceConnectionTestResult(true, "Verzeichnis erreichbar.", 3L))
                .getDetails())
        .isNull();
  }

  @Test
  void aListingRequestNamesItsTypeAndItsQuery() {
    UUID libraryId = UUID.randomUUID();
    SourceBrowseRequest request =
        new SourceBrowseRequest(URI.create("https://s3.example.org"))
            .sourceCredentials("ak:sk")
            .sourceProxy("proxy.example.com:8080")
            .sourceInsecureSsl(true)
            .query(Map.of("region", "eu-central-1", "pathStyle", true))
            .libraryId(libraryId);

    io.opaa.library.SourceBrowseRequest domain =
        SourceConnectionTestResponseMapper.toDomain("S3", request);

    assertThat(domain.sourceType()).isEqualTo(SourceTypes.S3);
    assertThat(domain.sourceUrl()).isEqualTo(URI.create("https://s3.example.org"));
    assertThat(domain.sourceCredentials()).isEqualTo("ak:sk");
    assertThat(domain.sourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(domain.sourceInsecureSsl()).isTrue();
    assertThat(domain.query().asMap())
        .containsEntry("region", "eu-central-1")
        .containsEntry("pathStyle", true);
    assertThat(domain.libraryId()).isEqualTo(libraryId);
    assertThatThrownBy(() -> SourceConnectionTestResponseMapper.toDomain("s3", request))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void aListingKeepsEveryEntryAndTheFallback() {
    SourceBrowseResponse listed =
        SourceConnectionTestResponseMapper.toResponse(
            new SourceListing(
                true,
                List.of(
                    new SourceListing.Entry("ENG", "Engineering"),
                    new SourceListing.Entry("archiv", null)),
                null));
    assertThat(listed.getComplete()).isTrue();
    assertThat(listed.getEntries())
        .extracting(SourceBrowseEntry::getKey, SourceBrowseEntry::getName)
        .containsExactly(tuple("ENG", "Engineering"), tuple("archiv", null));
    assertThat(listed.getMessage()).isNull();

    SourceBrowseResponse fallback =
        SourceConnectionTestResponseMapper.toResponse(
            new SourceListing(false, List.of(), "nicht lesbar"));
    assertThat(fallback.getComplete()).isFalse();
    assertThat(fallback.getEntries()).isEmpty();
    assertThat(fallback.getMessage()).isEqualTo("nicht lesbar");
  }

  @Test
  void aDescriptorNamesTypeNameAndAbilities() {
    SourceTypeDescriptor descriptor =
        SourceConnectionTestResponseMapper.toResponse(
            SourceConnectorDescriptor.remoteRun(SourceTypes.CONFLUENCE, "Confluence")
                .withPushIntake(new PushIntake("confluenceWebhookSecret"))
                .withFullSyncInterval(Duration.ofHours(36)),
            true,
            new TypeCreation(false, false, true, true, "Die Quellart ist gesperrt."));

    assertThat(descriptor.getCreatable()).isFalse();
    assertThat(descriptor.getCreatableWithOwnAddress()).isFalse();
    assertThat(descriptor.getLocked()).isTrue();
    assertThat(descriptor.getProfileRequired()).isTrue();
    assertThat(descriptor.getCreationNotice()).isEqualTo("Die Quellart ist gesperrt.");
    assertThat(descriptor.getType()).isEqualTo("CONFLUENCE");
    assertThat(descriptor.getDisplayName()).isEqualTo("Confluence");
    assertThat(descriptor.getIndexingRun()).isTrue();
    assertThat(descriptor.getUploads()).isFalse();
    assertThat(descriptor.getPushIntake()).isTrue();
    assertThat(descriptor.getBrowsable()).isTrue();
    assertThat(descriptor.getFullSyncIntervalDefaultDays()).isEqualTo(1);
  }

  @Test
  void toResponseLeavesDocumentCountNullForAnUnreachableSource() {
    SourceConnectionTestResponse response =
        SourceConnectionTestResponseMapper.toResponse(
            new SourceConnectionTestResult(false, "Das Verzeichnis existiert nicht.", null));

    assertThat(response.getReachable()).isFalse();
    assertThat(response.getDocumentCount()).isNull();
  }
}
