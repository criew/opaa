package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.opaa.api.dto.IndexingStatus;
import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibraryScheduleRequest;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.ExternalAccessState;
import io.opaa.api.types.ScheduleFrequency;
import io.opaa.api.types.ScheduleWeekday;
import io.opaa.api.types.SuccessionAddressee;
import io.opaa.common.ValidationException;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.confluence.ConfluenceConnectionService;
import io.opaa.indexing.source.confluence.ConfluenceEdition;
import io.opaa.indexing.source.confluence.ConfluenceProperties;
import io.opaa.indexing.source.confluence.ConfluenceSourceConnector;
import io.opaa.indexing.source.confluence.ConfluenceSpaceSelection;
import io.opaa.indexing.source.confluence.ConfluenceTestSettings;
import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookService;
import io.opaa.indexing.source.s3.S3ClientFactory;
import io.opaa.indexing.source.s3.S3ConnectionService;
import io.opaa.indexing.source.s3.S3OriginalAccess;
import io.opaa.indexing.source.s3.S3Properties;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3SourceConnector;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.indexing.source.s3.S3TestSettings;
import io.opaa.indexing.source.s3.events.S3EventService;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.library.LibraryCreation;
import io.opaa.library.LibraryDetail;
import io.opaa.library.LibraryExternalAccess;
import io.opaa.library.LibraryManagementDetail;
import io.opaa.library.LibraryScheduleDetail;
import io.opaa.library.LibrarySummary;
import io.opaa.library.LibraryUpdate;
import io.opaa.permission.AssetReach;
import io.opaa.permission.AssetType;
import io.opaa.permission.SuccessionFinding;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.SourceTypes;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

  private final S3SourceConnector s3Connector =
      new S3SourceConnector(
          mock(S3ConnectionService.class),
          new S3ClientFactory(S3Properties.defaults(), TargetAddressValidator.disabled()),
          mock(SourceSyncStateRepository.class),
          mock(S3OriginalAccess.class),
          mock(S3EventService.class));
  private final ConfluenceSourceConnector confluenceConnector =
      new ConfluenceSourceConnector(
          mock(ConfluenceConnectionService.class),
          new ConfluenceProperties(0, null, null, 0, null, 0, 0, 0, null, null, 0),
          mock(SourceSyncStateRepository.class),
          mock(ConfluenceWebhookService.class));

  @Test
  void toResponseCopiesLibraryAndDocumentCountFieldsForACallerBelowManager() {
    UUID owner = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(organization, "Rechtsquellen", "Beschreibung", owner, true);
    LibraryDetail detail =
        new LibraryDetail(
            library,
            AssetRole.VIEWER,
            7L,
            LibraryManagementDetail.EMPTY,
            false,
            new AssetReach(true, 2, 1),
            "Referat 50",
            null);

    LibraryResponse response = LibraryResponseMapper.toResponse(detail);

    assertThat(response.getId()).isEqualTo(library.getId());
    assertThat(response.getName()).isEqualTo("Rechtsquellen");
    assertThat(response.getDescription()).isEqualTo("Beschreibung");
    assertThat(response.getOwnerType()).isEqualTo(AssetOwnerType.USER);
    assertThat(response.getOwnerId()).isEqualTo(owner);
    // #1941: the owner's name reaches every reader - the section "Eigentümer" of the Reiter
    // „Freigaben" is not gated at MANAGER like the configuration fields below.
    assertThat(response.getOwnerName()).isEqualTo("Referat 50");
    assertThat(response.getReach().getAllAccounts()).isTrue();
    assertThat(response.getReach().getGroupCount()).isEqualTo(2);
    assertThat(response.getReach().getUserCount()).isEqualTo(1);
    assertThat(response.getListed()).isTrue();
    assertThat(response.getMyRole()).isEqualTo(AssetRole.VIEWER);
    assertThat(response.getSourceType()).isEqualTo("UPLOAD");
    assertThat(response.getDocumentCount()).isEqualTo(7L);
    // Leitplanke (e): every library starts diagnosegesperrt, and the state is readable by anyone
    // who may read the library - not only by whoever just set it through the lock endpoint. Both
    // states are asserted: a mapper reading the wrong field would match the entity's default.
    assertThat(response.getDiagnosticsLocked()).isTrue();
    library.setDiagnosticsLocked(false);
    assertThat(LibraryResponseMapper.toResponse(detail).getDiagnosticsLocked()).isFalse();
    // #507: a caller below MANAGER never sees sourcePath/sourceUrl/schedule/storage quota - every
    // LibraryManagementDetail field stays null even though the record itself is always present.
    assertThat(response.getSourcePath()).isNull();
    assertThat(response.getSourceUrl()).isNull();
    assertThat(response.getSourceProxy()).isNull();
    assertThat(response.getSourceCredentialsSet()).isNull();
    assertThat(response.getPushSecretSet()).isNull();
    assertThat(response.getSourceSettings()).isNull();
    assertThat(response.getSchedule()).isNull();
    assertThat(response.getLastScheduledRunsFailed()).isNull();
    assertThat(response.getStorageQuotaBytes()).isNull();
    assertThat(response.getStorageUsedBytes()).isNull();
  }

  // #1278 review: myRole alone (bypassed to OWNER for a system admin) must not be mistaken for
  // this field - a mapper reading myRole instead of LibraryDetail#diagnosticsLockToggleable would
  // still pass every other assertion in this file, since every existing detail's myRole already
  // matches the intended toggleable value.
  @Test
  void toResponseCopiesDiagnosticsLockToggleableIndependentlyOfMyRole() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(), "Rechtsquellen", null, UUID.randomUUID(), false);
    LibraryDetail toggleable =
        new LibraryDetail(
            library,
            AssetRole.OWNER,
            0L,
            LibraryManagementDetail.EMPTY,
            true,
            AssetReach.NONE,
            null,
            null);
    LibraryDetail notToggleable =
        new LibraryDetail(
            library,
            AssetRole.OWNER,
            0L,
            LibraryManagementDetail.EMPTY,
            false,
            AssetReach.NONE,
            null,
            null);

    assertThat(LibraryResponseMapper.toResponse(toggleable).getDiagnosticsLockToggleable())
        .isTrue();
    assertThat(LibraryResponseMapper.toResponse(notToggleable).getDiagnosticsLockToggleable())
        .isFalse();
  }

  @Test
  void toResponseCarriesManagementDetailFieldsForAManager() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Wiki",
            null,
            UUID.randomUUID(),
            false,
            SourceTypes.CONFLUENCE,
            null,
            "https://wiki.example.org",
            null,
            "pat",
            false);
    Instant nextRunAt = Instant.now().plusSeconds(3600);
    Instant releaseExpiresAt = Instant.now().plusSeconds(86_400);
    Instant releaseSetAt = Instant.now().minusSeconds(60);
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
            true,
            ConnectorData.of(Map.of("fullSyncIntervalDays", 14)),
            7,
            schedule,
            false,
            1_000_000L,
            250_000L,
            new LibraryExternalAccess(
                library.getId(),
                ExternalAccessState.ACTIVE,
                releaseExpiresAt,
                releaseSetAt,
                "Erika Mustermann",
                0L,
                365),
            false,
            false);
    LibraryDetail detail =
        new LibraryDetail(
            library, AssetRole.MANAGER, 3L, managementDetail, true, AssetReach.NONE, null, null);

    LibraryResponse response = LibraryResponseMapper.toResponse(detail);

    assertThat(response.getSourcePath()).isEqualTo("/data/documents");
    assertThat(response.getPushSecretSet()).isTrue();
    // #1200: distinct values prove both rhythm fields are carried, not aliased; a manager gets the
    // connector's whole settings
    assertThat(response.getSourceSettings()).containsEntry("fullSyncIntervalDays", 14);
    assertThat(response.getFullSyncIntervalDefaultDays()).isEqualTo(7);
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
    // #1731: every field of the release travels, and the token count stays a number
    assertThat(response.getExternalAccess().getLibraryId()).isEqualTo(library.getId());
    assertThat(response.getExternalAccess().getState()).isEqualTo(ExternalAccessState.ACTIVE);
    assertThat(response.getExternalAccess().getExpiresAt()).isEqualTo(releaseExpiresAt);
    assertThat(response.getExternalAccess().getSetAt()).isEqualTo(releaseSetAt);
    assertThat(response.getExternalAccess().getSetByDisplayName()).isEqualTo("Erika Mustermann");
    assertThat(response.getExternalAccess().getTokenCount()).isZero();
    assertThat(response.getExternalAccess().getMaxReleaseDays()).isEqualTo(365);
    // #797
    assertThat(response.getAllAccountsGrantAllowed()).isFalse();
    assertThat(response.getListedCap()).isFalse();
  }

  @Test
  void toResponseLeavesScheduleNullWhenTheLibraryCarriesNoneEvenForAManager() {
    // #485: an UPLOAD library never carries a schedule at all - the management detail's own
    // schedule field distinguishes that case from "not visible to this caller" (previous test).
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Uploads", null, UUID.randomUUID(), false);
    LibraryManagementDetail managementDetail =
        new LibraryManagementDetail(
            null, null, null, false, false, null, null, null, null, null, 0L, 0L, null, null, null);
    LibraryDetail detail =
        new LibraryDetail(
            library, AssetRole.OWNER, 0L, managementDetail, true, AssetReach.NONE, null, null);

    LibraryResponse response = LibraryResponseMapper.toResponse(detail);

    assertThat(response.getSchedule()).isNull();
    assertThat(response.getLastScheduledRunsFailed()).isNull();
    // #797: UPLOAD never carries a cap
    assertThat(response.getAllAccountsGrantAllowed()).isNull();
    assertThat(response.getListedCap()).isNull();
  }

  @Test
  void toListResponseCarriesTheResolvedOwnerName() {
    UUID owner = UUID.randomUUID();
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Team-Bibliothek", null, owner, true);
    LibrarySummary summary =
        new LibrarySummary(
            library,
            AssetRole.EDITOR,
            4L,
            "Referat 50",
            Instant.parse("2026-08-18T06:00:00Z"),
            JobStatus.COMPLETED,
            SuccessionFinding.ofAsset(
                AssetType.of("KNOWLEDGE_LIBRARY"),
                library.getId(),
                library.getName(),
                SuccessionAddressee.GROUP_STEWARDS),
            new AssetReach(false, 1, 3));

    var response = LibraryResponseMapper.toListResponse(summary);

    assertThat(response.getId()).isEqualTo(library.getId());
    assertThat(response.getName()).isEqualTo("Team-Bibliothek");
    assertThat(response.getMyRole()).isEqualTo(AssetRole.EDITOR);
    assertThat(response.getDocumentCount()).isEqualTo(4L);
    assertThat(response.getOwnerName()).isEqualTo("Referat 50");
    assertThat(response.getLastRunStatus()).isEqualTo(IndexingStatus.COMPLETED);
    assertThat(response.getSuccession().getAddressee())
        .as("the overview carries the marking as the detail view does (#1819)")
        .isEqualTo(SuccessionAddressee.GROUP_STEWARDS);
  }

  @Test
  void toListResponsesMapsEverySummaryInOrder() {
    KnowledgeLibrary first =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "A", null, UUID.randomUUID(), false);
    KnowledgeLibrary second =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "B", null, UUID.randomUUID(), false);
    List<LibrarySummary> summaries =
        List.of(
            new LibrarySummary(
                first, AssetRole.VIEWER, 0L, null, null, null, null, AssetReach.NONE),
            new LibrarySummary(
                second, AssetRole.OWNER, 1L, null, null, null, null, AssetReach.NONE));

    var responses = LibraryResponseMapper.toListResponses(summaries);

    assertThat(responses).extracting(r -> r.getName()).containsExactly("A", "B");
    assertThat(responses)
        .as("a library with a capable owner carries no marking at all")
        .allSatisfy(response -> assertThat(response.getSuccession()).isNull());
    assertThat(responses)
        .as("#1940: no run at all stays absent - the field never claims IDLE")
        .allSatisfy(response -> assertThat(response.getLastRunStatus()).isNull());
  }

  @Test
  void toListResponseNamesAFailedLastRunEvenWhenAnEarlierOneSucceeded() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Konnektor", null, UUID.randomUUID(), true);
    // #1940: exactly the picture the overview could not show before - lastIndexedAt still stands
    // at the last success while the newest run failed.
    LibrarySummary summary =
        new LibrarySummary(
            library,
            AssetRole.MANAGER,
            7L,
            null,
            Instant.parse("2026-09-20T04:00:00Z"),
            JobStatus.FAILED,
            null,
            AssetReach.NONE);

    var response = LibraryResponseMapper.toListResponse(summary);

    assertThat(response.getLastIndexedAt()).isEqualTo(Instant.parse("2026-09-20T04:00:00Z"));
    assertThat(response.getLastRunStatus()).isEqualTo(IndexingStatus.FAILED);
  }

  @Test
  void toListResponseCarriesARunningRun() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Laeuft", null, UUID.randomUUID(), true);
    LibrarySummary summary =
        new LibrarySummary(
            library, AssetRole.MANAGER, 0L, null, null, JobStatus.RUNNING, null, AssetReach.NONE);

    assertThat(LibraryResponseMapper.toListResponse(summary).getLastRunStatus())
        .isEqualTo(IndexingStatus.RUNNING);
  }

  @Test
  void toCreationCopiesEveryRequestField() {
    UUID ownerId = UUID.randomUUID();
    // #860 review, finding 2: every field below is deliberately a distinct value pairwise (in
    // particular sourcePath/sourceProxy/sourceCredentials, three plain strings) - a mapper that
    // swapped two of them would otherwise still pass with equal placeholder values.
    LibraryRequest request =
        new LibraryRequest("Rechtsquellen", "HTTP_DIRECTORY")
            .description("Beschreibung")
            .ownerType(AssetOwnerType.GROUP)
            .ownerId(ownerId)
            .listed(true)
            .sourcePath("/data/documents")
            .sourceUrl(URI.create("https://example.com/documents/"))
            .sourceProxy("proxy.example.com:8080")
            .sourceCredentials("admin:secret")
            .sourceInsecureSsl(true);

    LibraryCreation creation = LibraryResponseMapper.toCreation(request);

    assertThat(creation.name()).isEqualTo("Rechtsquellen");
    assertThat(creation.description()).isEqualTo("Beschreibung");
    assertThat(creation.ownerType()).isEqualTo(AssetOwnerType.GROUP);
    assertThat(creation.ownerId()).isEqualTo(ownerId);
    assertThat(creation.listed()).isTrue();
    assertThat(creation.sourceType()).isEqualTo(SourceTypes.HTTP_DIRECTORY);
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
            .listed(false)
            .sourceType("RSS_FEED")
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
    assertThat(update.listed()).isFalse();
    assertThat(update.sourceType()).isEqualTo(SourceTypes.RSS_FEED);
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
  void toCreationAndToUpdateCarryTheSettingsObjectAndAbsentStaysAbsent() {
    LibraryRequest request =
        new LibraryRequest("Wiki", "CONFLUENCE")
            .sourceUrl(URI.create("https://wiki.example.org"))
            .sourceCredentials("pat")
            .sourceSettings(
                Map.of("edition", "DATA_CENTER", "spaces", List.of(Map.of("key", "ENG"))));

    LibraryCreation creation = LibraryResponseMapper.toCreation(request);

    assertThat(creation.sourceType()).isEqualTo(SourceTypes.CONFLUENCE);
    assertThat(creation.sourceSettings().asMap())
        .containsEntry("edition", "DATA_CENTER")
        .containsKey("spaces");
    assertThat(
            LibraryResponseMapper.toUpdate(
                    new LibraryUpdateRequest("Wiki")
                        .sourceSettings(Map.of("fullSyncIntervalDays", 0)))
                .sourceSettings()
                .asMap())
        .containsEntry("fullSyncIntervalDays", 0);
    // absent means "leave the settings alone", not "clear them"
    assertThat(LibraryResponseMapper.toUpdate(new LibraryUpdateRequest("Wiki")).sourceSettings())
        .isNull();
  }

  @Test
  void aValueThatIsNoTypeKeyIsTheCallersFourHundred() {
    assertThatThrownBy(() -> LibraryResponseMapper.toCreation(new LibraryRequest("X", "wiki")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("kein gültiger Quellentyp-Schlüssel");
  }

  @Test
  void readersSeeWhatTheConnectorShowsAndAManagerTheWholeSettings() {
    KnowledgeLibrary confluence =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Wiki",
            null,
            UUID.randomUUID(),
            false,
            SourceTypes.CONFLUENCE,
            null,
            "https://wiki.example.org",
            null,
            "pat-geheim",
            false);
    ConfluenceTestSettings.configure(
        confluence,
        ConfluenceEdition.CLOUD,
        List.of(
            new ConfluenceSpaceSelection("HR", "Personal"),
            new ConfluenceSpaceSelection("ENG", null)));
    ConfluenceTestSettings.fullSyncIntervalDays(confluence, 14);

    LibraryResponse reader =
        LibraryResponseMapper.toResponse(
            new LibraryDetail(
                confluence,
                AssetRole.VIEWER,
                0,
                LibraryManagementDetail.EMPTY,
                false,
                AssetReach.NONE,
                null,
                confluenceConnector.settingsView(confluence, false)));

    assertThat(reader.getSourceType()).isEqualTo("CONFLUENCE");
    assertThat(reader.getSourceSettings())
        .containsEntry("edition", "CLOUD")
        .doesNotContainKey("fullSyncIntervalDays");
    assertThat(
            ((List<?>) reader.getSourceSettings().get("spaces"))
                .stream().<Object>map(space -> ((Map<?, ?>) space).get("key")).toList())
        .containsExactly("ENG", "HR");
    assertThat(reader.toString()).doesNotContain("pat-geheim");

    LibraryManagementDetail management =
        new LibraryManagementDetail(
            null,
            null,
            null,
            false,
            true,
            false,
            confluenceConnector.settingsView(confluence, true),
            7,
            null,
            null,
            0L,
            0L,
            null,
            true,
            true);
    LibraryResponse manager =
        LibraryResponseMapper.toResponse(
            new LibraryDetail(
                confluence,
                AssetRole.MANAGER,
                0,
                management,
                false,
                AssetReach.NONE,
                null,
                confluenceConnector.settingsView(confluence, false)));
    assertThat(manager.getSourceSettings()).containsEntry("fullSyncIntervalDays", 14);
    assertThat(manager.getPushSecretSet()).isFalse();
  }

  @Test
  void anS3LibraryShowsItsScopesToEveryReaderAndAnUploadLibraryNoSettings() {
    KnowledgeLibrary s3 =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Protokolle",
            null,
            UUID.randomUUID(),
            false,
            SourceTypes.S3,
            null,
            "https://s3.example.org",
            null,
            "AKIA:hochgeheim",
            false);
    S3TestSettings.configure(
        s3,
        new S3SourceSettings(
            "eu-central-1",
            true,
            List.of(S3Scope.of("protokolle", "2025/")),
            List.of("**/*.pdf"),
            null));

    LibraryResponse response =
        LibraryResponseMapper.toResponse(
            new LibraryDetail(
                s3,
                AssetRole.VIEWER,
                0,
                LibraryManagementDetail.EMPTY,
                false,
                AssetReach.NONE,
                null,
                s3Connector.settingsView(s3, false)));

    assertThat(response.getSourceSettings()).containsEntry("region", "eu-central-1");
    assertThat(response.toString()).doesNotContain("hochgeheim");

    KnowledgeLibrary upload =
        KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Upload", null, UUID.randomUUID(), false);
    assertThat(
            LibraryResponseMapper.toResponse(
                    new LibraryDetail(
                        upload,
                        AssetRole.OWNER,
                        0,
                        LibraryManagementDetail.EMPTY,
                        false,
                        AssetReach.NONE,
                        null,
                        null))
                .getSourceSettings())
        .isNull();
  }

  @Test
  void toUpdateLeavesScheduleNullWhenTheRequestOmitsIt() {
    LibraryUpdateRequest request = new LibraryUpdateRequest("Umbenannt");

    LibraryUpdate update = LibraryResponseMapper.toUpdate(request);

    assertThat(update.schedule()).isNull();
  }
}
