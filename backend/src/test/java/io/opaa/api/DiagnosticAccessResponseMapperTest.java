package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.DiagnosticContextEventPage;
import io.opaa.api.dto.DiagnosticContextEventResponse;
import io.opaa.api.dto.DiagnosticContextRetentionResponse;
import io.opaa.api.dto.DiagnosticImpersonationGrantResponse;
import io.opaa.api.dto.LibraryDiagnosticsLockResponse;
import io.opaa.api.dto.OwnDiagnosticContextEventPage;
import io.opaa.api.types.DiagnosticTargetKind;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.diagnosticaccess.DiagnosticContextLogEntry;
import io.opaa.diagnosticaccess.DiagnosticContextRetentionSettings;
import io.opaa.diagnosticaccess.DiagnosticImpersonationGrant;
import io.opaa.diagnosticaccess.OwnDiagnosticContextEvent;
import io.opaa.library.KnowledgeLibrary;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** Field-by-field mapping guard for {@link DiagnosticAccessResponseMapper}. */
class DiagnosticAccessResponseMapperTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  @Test
  void mapsEveryGrantField() {
    UUID holderId = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    UUID granterId = UUID.randomUUID();
    DiagnosticImpersonationGrant grant =
        new DiagnosticImpersonationGrant(
            ORGANIZATION_ID,
            holderId,
            scopeId,
            NOW.minus(1, ChronoUnit.DAYS),
            NOW.plus(30, ChronoUnit.DAYS),
            granterId,
            NOW.minus(1, ChronoUnit.DAYS));

    DiagnosticImpersonationGrantResponse response =
        DiagnosticAccessResponseMapper.toResponse(grant, NOW);

    assertThat(response.getId()).isEqualTo(grant.getId());
    assertThat(response.getHolderUserId()).isEqualTo(holderId);
    assertThat(response.getScopeGroupId()).isEqualTo(scopeId);
    assertThat(response.getValidFrom()).isEqualTo(NOW.minus(1, ChronoUnit.DAYS));
    assertThat(response.getValidUntil()).isEqualTo(NOW.plus(30, ChronoUnit.DAYS));
    assertThat(response.getGrantedByUserId()).isEqualTo(granterId);
    assertThat(response.getGrantedAt()).isEqualTo(NOW.minus(1, ChronoUnit.DAYS));
    assertThat(response.getRevokedAt()).isNull();
    assertThat(response.getActive()).isTrue();
  }

  @Test
  void aRevokedGrantIsNoLongerActive() {
    DiagnosticImpersonationGrant grant =
        new DiagnosticImpersonationGrant(
            ORGANIZATION_ID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            NOW.minus(1, ChronoUnit.DAYS),
            NOW.plus(30, ChronoUnit.DAYS),
            UUID.randomUUID(),
            NOW.minus(1, ChronoUnit.DAYS));
    grant.revoke(UUID.randomUUID(), NOW);

    DiagnosticImpersonationGrantResponse response =
        DiagnosticAccessResponseMapper.toResponse(grant, NOW);

    assertThat(response.getRevokedAt()).isEqualTo(NOW);
    assertThat(response.getActive()).isFalse();
  }

  /**
   * Every field of a stored entry that the Gesamtprotokoll list may show. The two it may not -
   * {@code targetRef} for a {@code USER} entry and {@code permissionSnapshot} - are asserted absent
   * here and in {@code DiagnosticContextPurposeLimitationTest}.
   */
  @Test
  void mapsEveryProtocolEntryFieldTheGesamtprotokollMayShow() {
    DiagnosticContextLogEntry entry =
        new DiagnosticContextLogEntry(
            ORGANIZATION_ID,
            "actor-pseudonym",
            DiagnosticTargetKind.USER,
            "target-pseudonym",
            "Wo steht die Dienstanweisung?",
            2,
            "chunk-1,chunk-2",
            "libraries=[];lockedLibraries=[]",
            "Beschwerde 4711");

    DiagnosticContextEventResponse response = DiagnosticAccessResponseMapper.toResponse(entry);

    assertThat(response.getEventId()).isEqualTo(entry.getEventId());
    assertThat(response.getActorRef()).isEqualTo("actor-pseudonym");
    assertThat(response.getTargetKind()).isEqualTo(DiagnosticTargetKind.USER);
    // Leitplanke (g): the per-person pseudonym never reaches the Gesamtprotokoll list, where a
    // client could group by it. Only the profile label does - see the sibling test below.
    assertThat(response.getTargetRef()).isNull();
    assertThat(response.getTestQuestion()).isEqualTo("Wo steht die Dienstanweisung?");
    assertThat(response.getHitCount()).isEqualTo(2);
    assertThat(response.getHitRefs()).isEqualTo("chunk-1,chunk-2");
    assertThat(response.getJustification()).isEqualTo("Beschwerde 4711");
  }

  @Test
  void keepsTheProfileLabelAsTargetRefBecauseAProfileBelongsToNobody() {
    DiagnosticContextLogEntry entry =
        new DiagnosticContextLogEntry(
            ORGANIZATION_ID,
            "actor-pseudonym",
            DiagnosticTargetKind.PERMISSION_PROFILE,
            "Sachbearbeitung Bauamt",
            "Wo steht die Dienstanweisung?",
            0,
            "",
            "libraries=[];lockedLibraries=[]",
            null);

    assertThat(DiagnosticAccessResponseMapper.toResponse(entry).getTargetRef())
        .isEqualTo("Sachbearbeitung Bauamt");
  }

  @Test
  void mapsTheOwnEventViewAndItsPage() {
    OwnDiagnosticContextEventPage page =
        DiagnosticAccessResponseMapper.toOwnPage(
            new PageImpl<>(
                List.of(new OwnDiagnosticContextEvent(NOW, "Frau Beispiel", "Beschwerde 4711")),
                PageRequest.of(0, 50),
                1));

    assertThat(page.getPage()).isZero();
    assertThat(page.getSize()).isEqualTo(50);
    assertThat(page.getHasMore()).isFalse();
    assertThat(page.getEvents()).hasSize(1);
    assertThat(page.getEvents().getFirst().getRecordedAt()).isEqualTo(NOW);
    assertThat(page.getEvents().getFirst().getActorDisplayName()).isEqualTo("Frau Beispiel");
    assertThat(page.getEvents().getFirst().getJustification()).isEqualTo("Beschwerde 4711");
  }

  @Test
  void mapsTheProtocolPageWithoutAnyAggregate() {
    DiagnosticContextEventPage page =
        DiagnosticAccessResponseMapper.toPage(
            new PageImpl<>(List.of(), PageRequest.of(1, 25), 100));

    assertThat(page.getPage()).isEqualTo(1);
    assertThat(page.getSize()).isEqualTo(25);
    assertThat(page.getHasMore()).isTrue();
    assertThat(page.getEvents()).isEmpty();
  }

  @Test
  void mapsTheRetentionSettings() throws Exception {
    // The entity is read-only end to end (no setters, every column insertable=false) because only
    // the database function writes it - reflection is the only way to build one outside JPA.
    java.lang.reflect.Constructor<DiagnosticContextRetentionSettings> constructor =
        DiagnosticContextRetentionSettings.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    DiagnosticContextRetentionSettings settings = constructor.newInstance();
    set(settings, "retentionMonths", 12);
    set(settings, "lastCutoff", NOW.minus(365, ChronoUnit.DAYS));
    set(settings, "updatedAt", NOW);

    DiagnosticContextRetentionResponse response =
        DiagnosticAccessResponseMapper.toResponse(settings);

    assertThat(response.getRetentionMonths()).isEqualTo(12);
    assertThat(response.getLastCutoff()).isEqualTo(NOW.minus(365, ChronoUnit.DAYS));
    assertThat(response.getUpdatedAt()).isEqualTo(NOW);
  }

  private static void set(Object target, String field, Object value) throws Exception {
    java.lang.reflect.Field declared = target.getClass().getDeclaredField(field);
    declared.setAccessible(true);
    declared.set(target, value);
  }

  @Test
  void mapsTheDiagnosticsLock() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            ORGANIZATION_ID,
            "Personalvorgänge",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false);

    LibraryDiagnosticsLockResponse response = DiagnosticAccessResponseMapper.toResponse(library);

    assertThat(response.getLibraryId()).isEqualTo(library.getId());
    assertThat(response.getLocked()).isTrue();
  }
}
