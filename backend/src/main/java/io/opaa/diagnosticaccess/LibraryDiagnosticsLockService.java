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
 * administration's. {@link #setLocked} resolves the caller's role with {@code systemAdmin = false},
 * so {@code SYSTEM_ADMIN} confers nothing here and an administrator without an {@link
 * AssetRole#OWNER} grant on the library cannot lift a lock they did not set. An administrator
 * privilege that could unlock a foreign Bestand would remove the protection entirely.
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

    // Deliberately systemAdmin = false: see the class Javadoc.
    AssetRole role = accessService.effectiveRole(library, actor.id(), false);
    if (role == null || !role.atLeast(AssetRole.OWNER)) {
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
