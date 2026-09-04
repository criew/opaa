package io.opaa.api;

import io.opaa.api.dto.DiagnosticContextEventDetailResponse;
import io.opaa.api.dto.DiagnosticContextEventPage;
import io.opaa.api.dto.DiagnosticContextEventResponse;
import io.opaa.api.dto.DiagnosticContextRetentionResponse;
import io.opaa.api.dto.DiagnosticImpersonationGrantListResponse;
import io.opaa.api.dto.DiagnosticImpersonationGrantResponse;
import io.opaa.api.dto.LibraryDiagnosticsLockResponse;
import io.opaa.api.dto.OwnDiagnosticContextEventPage;
import io.opaa.api.dto.OwnDiagnosticContextEventResponse;
import io.opaa.api.types.DiagnosticTargetKind;
import io.opaa.diagnosticaccess.DiagnosticContextLogEntry;
import io.opaa.diagnosticaccess.DiagnosticContextRetentionSettings;
import io.opaa.diagnosticaccess.DiagnosticImpersonationGrant;
import io.opaa.diagnosticaccess.OwnDiagnosticContextEvent;
import io.opaa.library.KnowledgeLibrary;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Entity/domain-record to response mapping for the "Sicht als" befugnis- and protocol model. The
 * two page mappings carry {@code events/page/size/hasMore} and nothing else - no total per target
 * person and no aggregate of any kind, which is Leitplanke (g) expressed in the response shape.
 */
final class DiagnosticAccessResponseMapper {

  private DiagnosticAccessResponseMapper() {}

  static DiagnosticImpersonationGrantResponse toResponse(
      DiagnosticImpersonationGrant grant, Instant now) {
    return new DiagnosticImpersonationGrantResponse(
            grant.getId(),
            grant.getHolderUserId(),
            grant.getScopeGroupId(),
            grant.getValidFrom(),
            grant.getValidUntil(),
            grant.getGrantedByUserId(),
            grant.getGrantedAt(),
            grant.isActiveAt(now))
        .revokedAt(grant.getRevokedAt());
  }

  static DiagnosticImpersonationGrantListResponse toListResponse(
      List<DiagnosticImpersonationGrant> grants, Instant now) {
    return new DiagnosticImpersonationGrantListResponse(
        grants.stream().map(grant -> toResponse(grant, now)).toList());
  }

  static DiagnosticContextRetentionResponse toResponse(
      DiagnosticContextRetentionSettings settings) {
    return new DiagnosticContextRetentionResponse(
            settings.getRetentionMonths(), settings.getUpdatedAt())
        .lastCutoff(settings.getLastCutoff());
  }

  static LibraryDiagnosticsLockResponse toResponse(KnowledgeLibrary library) {
    return new LibraryDiagnosticsLockResponse(library.getId(), library.isDiagnosticsLocked());
  }

  static OwnDiagnosticContextEventPage toOwnPage(Page<OwnDiagnosticContextEvent> page) {
    return new OwnDiagnosticContextEventPage(
        page.getContent().stream().map(DiagnosticAccessResponseMapper::toResponse).toList(),
        page.getNumber(),
        page.getSize(),
        page.hasNext());
  }

  static OwnDiagnosticContextEventResponse toResponse(OwnDiagnosticContextEvent event) {
    return new OwnDiagnosticContextEventResponse(event.recordedAt())
        .actorDisplayName(event.actorDisplayName())
        .justification(event.justification());
  }

  static DiagnosticContextEventPage toPage(Page<DiagnosticContextLogEntry> page) {
    return new DiagnosticContextEventPage(
        page.getContent().stream().map(DiagnosticAccessResponseMapper::toResponse).toList(),
        page.getNumber(),
        page.getSize(),
        page.hasNext());
  }

  /**
   * Withholds the two stable per-person keys of a stored entry, each of which would make "Diagnosen
   * je Nutzer" a one-step client-side {@code GROUP BY} - the evaluation Leitplanke (g) rules out:
   *
   * <ul>
   *   <li>{@code targetRef} is passed on only for a {@link DiagnosticTargetKind#PERMISSION_PROFILE}
   *       entry, where it is the profile's label and belongs to nobody. For a {@code USER} entry it
   *       is a stable per-person pseudonym and is dropped.
   *   <li>{@code permissionSnapshot} is not passed on at all: it is the rights fingerprint of one
   *       person and stays byte-identical across every entry about them until their rights change,
   *       so it is the same grouping key under another name. Hashing it would not help, for the
   *       same reason. It remains in the stored entry, which is where Leitplanke (f) requires it,
   *       and {@link #toDetailResponse} shows it for one entry at a time - which is the
   *       anlassbezogene Auswertung Leitplanke (g) allows, not a grouping key.
   * </ul>
   *
   * <p>This is not a claim that individual rows cannot be correlated: {@code recordedAt}, {@code
   * actorRef} and {@code hitRefs} remain, and the same test question against the same rights yields
   * the same {@code hitRefs} twice. Withholding the two keys costs a grouping client one step only
   * as long as the test questions vary - against a single, standardised test question {@code
   * hitRefs} groups in one step by itself.
   */
  static DiagnosticContextEventResponse toResponse(DiagnosticContextLogEntry entry) {
    return new DiagnosticContextEventResponse(
            entry.getEventId(),
            entry.getRecordedAt(),
            entry.getActorRef(),
            entry.getTargetKind(),
            entry.getTestQuestion(),
            entry.getHitCount(),
            entry.getHitRefs())
        .targetRef(
            entry.getTargetKind() == DiagnosticTargetKind.PERMISSION_PROFILE
                ? entry.getTargetRef()
                : null)
        .justification(entry.getJustification());
  }

  /**
   * The single-entry view, which additionally carries {@code permissionSnapshot}. The objection
   * against that field is a grouping objection, and it does not apply to one entry fetched under
   * its own Anlass and its own audit_log record; {@code targetRef} follows the same rule as the
   * list above, since nothing about a single entry makes a person's pseudonym useful here.
   */
  static DiagnosticContextEventDetailResponse toDetailResponse(DiagnosticContextLogEntry entry) {
    return new DiagnosticContextEventDetailResponse(
            entry.getEventId(),
            entry.getRecordedAt(),
            entry.getActorRef(),
            entry.getTargetKind(),
            entry.getTestQuestion(),
            entry.getHitCount(),
            entry.getHitRefs(),
            entry.getPermissionSnapshot())
        .targetRef(
            entry.getTargetKind() == DiagnosticTargetKind.PERMISSION_PROFILE
                ? entry.getTargetRef()
                : null)
        .justification(entry.getJustification());
  }
}
