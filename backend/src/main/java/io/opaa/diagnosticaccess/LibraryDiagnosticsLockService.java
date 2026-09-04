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
 * <p>What this does <b>not</b> cover, stated plainly - and it is not merely "takes a second
 * person":
 *
 * <ul>
 *   <li>An administrator can grant {@code OWNER} to a <em>different</em> account and have that
 *       account lift the lock. Both acts are audited.
 *   <li>Where the library is owned by a group that is not an {@code ORG_UNIT}, an administrator can
 *       add <em>themselves</em> to that owning group ({@code GroupController#addMember}, open to
 *       {@code SYSTEM_ADMIN}, with no self-exclusion), thereby becoming the library's named
 *       responsible body, and lift the lock alone - see {@link
 *       LibraryAccessService#holdsIndependentOwnerRole}. This path stays open by decision, not by
 *       omission: docs/features/hybrid-retrieval.md, Berechtigungs-Leitplanken (e).
 * </ul>
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
   * Whether this one library is diagnosegesperrt. The answer follows from the library alone; it is
   * never intersected with anybody's read rights, so no answer derived from it can carry a
   * statement about a person.
   */
  @Transactional(readOnly = true)
  public boolean isLocked(UUID libraryId) {
    return libraryId != null
        && libraryRepository
            .findById(libraryId)
            .map(KnowledgeLibrary::isDiagnosticsLocked)
            .orElse(false);
  }

  /**
   * How many libraries of one organization are diagnosegesperrt - the whole bestand, deliberately
   * without a rights intersection, so the number a diagnosis reports is the same for every target
   * person.
   */
  @Transactional(readOnly = true)
  public long countLocked(UUID organizationId) {
    return libraryRepository.countByOrganizationIdAndDiagnosticsLockedTrue(organizationId);
  }

  /**
   * Of {@code candidateLibraryIds}, those that are diagnosegesperrt. Used to subtract them from a
   * foreign context's searchable set and to record them in the rights snapshot of the protocol
   * entry. Because it is a rights intersection, it must not be surfaced in a diagnosis answer.
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
