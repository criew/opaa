package io.opaa.diagnosticaccess;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets and lifts a library's Diagnosesperre (Leitplanke (e)).
 *
 * <p>The single rule this class exists for: the lock is the responsible body's, not the
 * administration's. {@link #setLocked} asks {@link LibraryAccessService#holdsIndependentOwnerRole},
 * which knows no system-admin floor and discounts an {@link AssetRole#OWNER} grant the caller
 * issued to themselves - otherwise an administrator could reach a foreign lock in two steps, by
 * granting themselves {@code OWNER} through the administrative floor of the grant endpoint and then
 * lifting the lock as "the owner".
 *
 * <p>What this does <b>not</b> cover, stated plainly: an administrator can still grant {@code
 * OWNER} to a <em>different</em> account and have that account lift the lock. The rule enforced
 * here is that lifting a foreign lock takes a second, named person and leaves an audit trail on
 * both acts - not that it is impossible for an administrator with a second account.
 */
@Service
public class LibraryDiagnosticsLockService {

  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService accessService;
  private final AuditEventRecorder auditEventRecorder;

  public LibraryDiagnosticsLockService(
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService accessService,
      AuditEventRecorder auditEventRecorder) {
    this.libraryRepository = libraryRepository;
    this.accessService = accessService;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Transactional
  public KnowledgeLibrary setLocked(CurrentUser actor, UUID libraryId, boolean locked) {
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .filter(candidate -> actor.organizationId().equals(candidate.getOrganizationId()))
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));

    // Deliberately not effectiveRole/requireRole: those fail open for a system admin, and an
    // OWNER grant is self-issuable through that floor - see the class Javadoc.
    if (!accessService.holdsIndependentOwnerRole(library, actor.id())) {
      throw new AccessDeniedException(
          "Die Diagnosesperre setzt und löst nur die für die Bibliothek zuständige Stelle");
    }

    boolean previous = library.isDiagnosticsLocked();
    library.setDiagnosticsLocked(locked);
    KnowledgeLibrary saved = libraryRepository.save(library);
    if (previous != locked) {
      auditEventRecorder.recordUserAction(
          AuditEvent.builder()
              .organizationId(saved.getOrganizationId())
              .actor(actor.id())
              .type(AuditEventType.LIBRARY_DIAGNOSTICS_LOCK_CHANGED)
              .object(AuditObjectType.KNOWLEDGE_LIBRARY, saved.getId(), saved.getName())
              .before(Map.of("diagnosticsLocked", previous))
              .after(Map.of("diagnosticsLocked", locked))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    return saved;
  }

  /**
   * Of {@code candidateLibraryIds}, those that are diagnosegesperrt. Used to subtract them from a
   * foreign context's searchable set and to name them as "gesperrter Suchbereich" - the caller
   * learns that a locked area exists, never what is in it.
   */
  @Transactional(readOnly = true)
  public Set<UUID> lockedAmong(Set<UUID> candidateLibraryIds) {
    if (candidateLibraryIds.isEmpty()) {
      return Set.of();
    }
    return libraryRepository.findAllById(candidateLibraryIds).stream()
        .filter(KnowledgeLibrary::isDiagnosticsLocked)
        .map(KnowledgeLibrary::getId)
        .collect(Collectors.toUnmodifiableSet());
  }
}
