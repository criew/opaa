package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.ExternalAccessLibraryResponse;
import io.opaa.api.dto.LibraryExternalAccessResponse;
import io.opaa.api.types.ExternalAccessState;
import io.opaa.library.ExternalAccessLibrary;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryExternalAccess;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins that every field of the release reaches the wire (AGENTS.md, "API &amp; DTO-Konvention": a
 * response field nobody maps is a field nobody misses). The distinct values matter - two fields
 * aliased onto one another would pass an assertion built on equal ones.
 */
class LibraryExternalAccessResponseMapperTest {

  @Test
  void toResponseCarriesEveryFieldOfTheRelease() {
    UUID libraryId = UUID.randomUUID();
    Instant expiresAt = Instant.parse("2027-09-17T21:59:59Z");
    Instant setAt = Instant.parse("2026-09-18T08:14:00Z");

    LibraryExternalAccessResponse response =
        LibraryExternalAccessResponseMapper.toResponse(
            new LibraryExternalAccess(
                libraryId,
                ExternalAccessState.ACTIVE,
                expiresAt,
                setAt,
                "Erika Mustermann",
                4L,
                365));

    assertThat(response.getLibraryId()).isEqualTo(libraryId);
    assertThat(response.getState()).isEqualTo(ExternalAccessState.ACTIVE);
    assertThat(response.getExpiresAt()).isEqualTo(expiresAt);
    assertThat(response.getSetAt()).isEqualTo(setAt);
    assertThat(response.getSetByDisplayName()).isEqualTo("Erika Mustermann");
    assertThat(response.getTokenCount()).isEqualTo(4L);
    assertThat(response.getMaxReleaseDays()).isEqualTo(365);
  }

  /**
   * A release nobody ever set carries no date and no name - and says so rather than omitting it.
   */
  @Test
  void toResponseLeavesTheOptionalFieldsNullForAReleaseNobodyEverSet() {
    LibraryExternalAccessResponse response =
        LibraryExternalAccessResponseMapper.toResponse(
            new LibraryExternalAccess(
                UUID.randomUUID(), ExternalAccessState.NEVER_SET, null, null, null, 0L, 365));

    assertThat(response.getState()).isEqualTo(ExternalAccessState.NEVER_SET);
    assertThat(response.getExpiresAt()).isNull();
    assertThat(response.getSetAt()).isNull();
    assertThat(response.getSetByDisplayName()).isNull();
    assertThat(response.getTokenCount()).isZero();
  }

  @Test
  void toListResponseNamesTheLibraryBesideItsRelease() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(), "Baugenehmigungen 2024", null, UUID.randomUUID(), false);
    LibraryExternalAccess access =
        new LibraryExternalAccess(
            library.getId(),
            ExternalAccessState.ACTIVE,
            Instant.now(),
            Instant.now(),
            null,
            0L,
            365);

    List<ExternalAccessLibraryResponse> responses =
        LibraryExternalAccessResponseMapper.toListResponses(
            List.of(new ExternalAccessLibrary(library, access)));

    assertThat(responses).hasSize(1);
    assertThat(responses.getFirst().getLibraryName()).isEqualTo("Baugenehmigungen 2024");
    assertThat(responses.getFirst().getLibraryId()).isEqualTo(library.getId());
    assertThat(responses.getFirst().getExternalAccess().getState())
        .isEqualTo(ExternalAccessState.ACTIVE);
  }
}
