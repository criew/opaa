package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.opaa.api.dto.ConfluenceSpaceListRequest;
import io.opaa.api.dto.ConfluenceSpaceListResponse;
import io.opaa.api.dto.ConfluenceSpaceRef;
import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.source.confluence.ConfluenceSpace;
import io.opaa.library.ConfluenceSpaceListing;
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
