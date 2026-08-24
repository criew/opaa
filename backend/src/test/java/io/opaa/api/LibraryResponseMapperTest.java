package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibraryScheduleRequest;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.api.dto.ScheduleFrequency;
import io.opaa.api.dto.ScheduleWeekday;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.library.AssetRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryCreation;
import io.opaa.library.LibraryDetail;
import io.opaa.library.LibraryManagementDetail;
import io.opaa.library.LibraryOwnerType;
import io.opaa.library.LibraryScheduleDetail;
import io.opaa.library.LibrarySummary;
import io.opaa.library.LibraryUpdate;
import io.opaa.library.LibraryVisibility;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests (no Spring context) against directly constructed entities/records - the mapper
 * counterpart of {@code SpaceResponseMapperTest} (#860): pins {@link LibraryResponseMapper}'s
 * field-by-field behaviour, in particular that {@link LibraryDetail#managementDetail()} being
 * {@link LibraryManagementDetail#EMPTY} (a caller below {@code MANAGER} - never {@code null}, which
 * would NPE every field access) leaves every management-only field absent instead of throwing or
 * fabricating a value.
 */
class LibraryResponseMapperTest {

  @Test
  void toResponseCopiesLibraryAndDocumentCountFieldsForACallerBelowManager() {
    UUID owner = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organization,
            "Rechtsquellen",
            "Beschreibung",
            owner,
            LibraryVisibility.ORGANIZATION,
            true);
    LibraryDetail detail =
        new LibraryDetail(library, AssetRole.VIEWER, 7L, LibraryManagementDetail.EMPTY);

    LibraryResponse response = LibraryResponseMapper.toResponse(detail);

    assertThat(response.getId()).isEqualTo(library.getId());
    assertThat(response.getName()).isEqualTo("Rechtsquellen");
    assertThat(response.getDescription()).isEqualTo("Beschreibung");
    assertThat(response.getOwnerType()).isEqualTo(LibraryOwnerType.USER);
    assertThat(response.getOwnerId()).isEqualTo(owner);
    assertThat(response.getVisibility()).isEqualTo(LibraryVisibility.ORGANIZATION);
    assertThat(response.getListed()).isTrue();
    assertThat(response.getMyRole()).isEqualTo(AssetRole.VIEWER);
    assertThat(response.getSourceType()).isEqualTo(DocumentSourceType.UPLOAD);
    assertThat(response.getDocumentCount()).isEqualTo(7L);
    // #507: a caller below MANAGER never sees sourcePath/sourceUrl/schedule/storage quota - every
    // LibraryManagementDetail field stays null even though the record itself is always present.
    assertThat(response.getSourcePath()).isNull();
    assertThat(response.getSourceUrl()).isNull();
    assertThat(response.getSourceProxy()).isNull();
    assertThat(response.getSourceCredentialsSet()).isNull();
    assertThat(response.getSchedule()).isNull();
    assertThat(response.getLastScheduledRunsFailed()).isNull();
    assertThat(response.getStorageQuotaBytes()).isNull();
    assertThat(response.getStorageUsedBytes()).isNull();
  }

  @Test
  void toResponseCarriesManagementDetailFieldsForAManager() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Web-Verzeichnis",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false);
    Instant nextRunAt = Instant.now().plusSeconds(3600);
    LibraryScheduleDetail schedule =
        new LibraryScheduleDetail(
            ScheduleFrequency.DAILY, 3, 30, ScheduleWeekday.MONDAY, nextRunAt);
    LibraryManagementDetail managementDetail =
        new LibraryManagementDetail(
            "/data/documents",
            "https://example.com/documents/",
            "proxy.example.com:8080",
            true,
            true,
            schedule,
            false,
            1_000_000L,
            250_000L);
    LibraryDetail detail = new LibraryDetail(library, AssetRole.MANAGER, 3L, managementDetail);

    LibraryResponse response = LibraryResponseMapper.toResponse(detail);

    assertThat(response.getSourcePath()).isEqualTo("/data/documents");
    assertThat(response.getSourceUrl()).isEqualTo(URI.create("https://example.com/documents/"));
    assertThat(response.getSourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(response.getSourceInsecureSsl()).isTrue();
    assertThat(response.getSourceCredentialsSet()).isTrue();
    assertThat(response.getSchedule().getFrequency()).isEqualTo(ScheduleFrequency.DAILY);
    assertThat(response.getSchedule().getHour()).isEqualTo(3);
    assertThat(response.getSchedule().getMinute()).isEqualTo(30);
    assertThat(response.getSchedule().getWeekday()).isEqualTo(ScheduleWeekday.MONDAY);
    assertThat(response.getSchedule().getNextRunAt()).isEqualTo(nextRunAt);
    assertThat(response.getLastScheduledRunsFailed()).isFalse();
    assertThat(response.getStorageQuotaBytes()).isEqualTo(1_000_000L);
    assertThat(response.getStorageUsedBytes()).isEqualTo(250_000L);
  }

  @Test
  void toResponseLeavesScheduleNullWhenTheLibraryCarriesNoneEvenForAManager() {
    // #485: an UPLOAD library never carries a schedule at all - the management detail's own
    // schedule field distinguishes that case from "not visible to this caller" (previous test).
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Uploads",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false);
    LibraryManagementDetail managementDetail =
        new LibraryManagementDetail(null, null, null, false, false, null, null, 0L, 0L);
    LibraryDetail detail = new LibraryDetail(library, AssetRole.OWNER, 0L, managementDetail);

    LibraryResponse response = LibraryResponseMapper.toResponse(detail);

    assertThat(response.getSchedule()).isNull();
    assertThat(response.getLastScheduledRunsFailed()).isNull();
  }

  @Test
  void toListResponseCarriesTheResolvedOwnerName() {
    UUID owner = UUID.randomUUID();
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(), "Team-Bibliothek", null, owner, LibraryVisibility.SHARED, true);
    LibrarySummary summary = new LibrarySummary(library, AssetRole.EDITOR, 4L, "Referat 50");

    var response = LibraryResponseMapper.toListResponse(summary);

    assertThat(response.getId()).isEqualTo(library.getId());
    assertThat(response.getName()).isEqualTo("Team-Bibliothek");
    assertThat(response.getMyRole()).isEqualTo(AssetRole.EDITOR);
    assertThat(response.getDocumentCount()).isEqualTo(4L);
    assertThat(response.getOwnerName()).isEqualTo("Referat 50");
  }

  @Test
  void toListResponsesMapsEverySummaryInOrder() {
    KnowledgeLibrary first =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(), "A", null, UUID.randomUUID(), LibraryVisibility.PRIVATE, false);
    KnowledgeLibrary second =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(), "B", null, UUID.randomUUID(), LibraryVisibility.PRIVATE, false);
    List<LibrarySummary> summaries =
        List.of(
            new LibrarySummary(first, AssetRole.VIEWER, 0L, null),
            new LibrarySummary(second, AssetRole.OWNER, 1L, null));

    var responses = LibraryResponseMapper.toListResponses(summaries);

    assertThat(responses).extracting(r -> r.getName()).containsExactly("A", "B");
  }

  @Test
  void toCreationCopiesEveryRequestField() {
    UUID ownerId = UUID.randomUUID();
    // #860 review, finding 2: every field below is deliberately a distinct value pairwise (in
    // particular sourcePath/sourceProxy/sourceCredentials, three plain strings) - a mapper that
    // swapped two of them would otherwise still pass with equal placeholder values.
    LibraryRequest request =
        new LibraryRequest("Rechtsquellen", DocumentSourceType.HTTP_DIRECTORY)
            .description("Beschreibung")
            .ownerType(LibraryOwnerType.GROUP)
            .ownerId(ownerId)
            .visibility(LibraryVisibility.ORGANIZATION)
            .listed(true)
            .sourcePath("/data/documents")
            .sourceUrl(URI.create("https://example.com/documents/"))
            .sourceProxy("proxy.example.com:8080")
            .sourceCredentials("admin:secret")
            .sourceInsecureSsl(true);

    LibraryCreation creation = LibraryResponseMapper.toCreation(request);

    assertThat(creation.name()).isEqualTo("Rechtsquellen");
    assertThat(creation.description()).isEqualTo("Beschreibung");
    assertThat(creation.ownerType()).isEqualTo(LibraryOwnerType.GROUP);
    assertThat(creation.ownerId()).isEqualTo(ownerId);
    assertThat(creation.visibility()).isEqualTo(LibraryVisibility.ORGANIZATION);
    assertThat(creation.listed()).isTrue();
    assertThat(creation.sourceType()).isEqualTo(DocumentSourceType.HTTP_DIRECTORY);
    assertThat(creation.sourcePath()).isEqualTo("/data/documents");
    assertThat(creation.sourceUrl()).isEqualTo(URI.create("https://example.com/documents/"));
    assertThat(creation.sourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(creation.sourceCredentials()).isEqualTo("admin:secret");
    assertThat(creation.sourceInsecureSsl()).isTrue();
  }

  @Test
  void toUpdateCopiesEveryRequestFieldIncludingTheSchedule() {
    // #860 review, finding 2: sourcePath/sourceProxy/sourceCredentials are three distinct plain
    // strings on purpose - see the identical reasoning on toCreationCopiesEveryRequestField above.
    LibraryUpdateRequest request =
        new LibraryUpdateRequest("Umbenannt")
            .description("Neue Beschreibung")
            .visibility(LibraryVisibility.PRIVATE)
            .listed(false)
            .sourceType(DocumentSourceType.RSS_FEED)
            .sourcePath("/data/documents")
            .sourceUrl(URI.create("https://example.com/feed.xml"))
            .sourceProxy("proxy.example.com:8080")
            .sourceCredentials("admin:secret")
            .sourceInsecureSsl(true)
            .schedule(
                new LibraryScheduleRequest(ScheduleFrequency.WEEKLY)
                    .hour(6)
                    .minute(0)
                    .weekday(ScheduleWeekday.FRIDAY));

    LibraryUpdate update = LibraryResponseMapper.toUpdate(request);

    assertThat(update.name()).isEqualTo("Umbenannt");
    assertThat(update.description()).isEqualTo("Neue Beschreibung");
    assertThat(update.visibility()).isEqualTo(LibraryVisibility.PRIVATE);
    assertThat(update.listed()).isFalse();
    assertThat(update.sourceType()).isEqualTo(DocumentSourceType.RSS_FEED);
    assertThat(update.sourcePath()).isEqualTo("/data/documents");
    assertThat(update.sourceUrl()).isEqualTo(URI.create("https://example.com/feed.xml"));
    assertThat(update.sourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(update.sourceCredentials()).isEqualTo("admin:secret");
    assertThat(update.sourceInsecureSsl()).isTrue();
    assertThat(update.schedule().frequency()).isEqualTo(ScheduleFrequency.WEEKLY);
    assertThat(update.schedule().hour()).isEqualTo(6);
    assertThat(update.schedule().minute()).isEqualTo(0);
    assertThat(update.schedule().weekday()).isEqualTo(ScheduleWeekday.FRIDAY);
  }

  @Test
  void toUpdateLeavesScheduleNullWhenTheRequestOmitsIt() {
    LibraryUpdateRequest request = new LibraryUpdateRequest("Umbenannt");

    LibraryUpdate update = LibraryResponseMapper.toUpdate(request);

    assertThat(update.schedule()).isNull();
  }
}
