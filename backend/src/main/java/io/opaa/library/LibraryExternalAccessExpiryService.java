package io.opaa.library;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.ExternalAccessState;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets an expired Freigabe für Fremdzugänge stop taking effect without anyone acting (#1731,
 * docs/features/external-access.md): the mandatory Befristung is the one measure that keeps the
 * Bestand of releases from ratcheting upwards, and it only is one if nobody has to remember it.
 *
 * <p>Writes both halves the specification requires, the way {@code
 * KnowledgeLibraryService#deleteLibrary} does rather than through {@link LibraryChanged}: the
 * history interval carries {@link LibraryVisibilityHistoryCause#EXTERNAL_ACCESS_EXPIRED} with no
 * actor, and the audit entry is a system-process action - a run has no acting person, and an expiry
 * recorded under the last person to touch the release would name someone who did nothing.
 *
 * <p><b>A Nachtrag, not the condition of the effect:</b> {@link
 * KnowledgeLibrary#isExternalAccessActive(Instant)} already answers {@code false} the moment the
 * Befristung passes, so a run that comes hours late - or, with the instance down, days late - never
 * leaves a release in effect past its own end. What this run adds is the recorded fact.
 *
 * <p>Idempotent: a release already out of effect is not selected, so a second run in the same
 * minute writes nothing. The Anlass is the Fristablauf; "gesenkte Freigabe-Obergrenze" comes with
 * #797.
 */
@Service
public class LibraryExternalAccessExpiryService {

  static final String EXTERNAL_ACCESS_ACTOR = "external-access-expiry";

  private static final Logger log =
      LoggerFactory.getLogger(LibraryExternalAccessExpiryService.class);

  private final KnowledgeLibraryRepository libraryRepository;
  private final PermissionHistoryService permissionHistoryService;
  private final AuditEventRecorder auditEventRecorder;
  private final InstantSource clock;

  @Autowired
  public LibraryExternalAccessExpiryService(
      KnowledgeLibraryRepository libraryRepository,
      PermissionHistoryService permissionHistoryService,
      AuditEventRecorder auditEventRecorder) {
    this(libraryRepository, permissionHistoryService, auditEventRecorder, InstantSource.system());
  }

  LibraryExternalAccessExpiryService(
      KnowledgeLibraryRepository libraryRepository,
      PermissionHistoryService permissionHistoryService,
      AuditEventRecorder auditEventRecorder,
      InstantSource clock) {
    this.libraryRepository = libraryRepository;
    this.permissionHistoryService = permissionHistoryService;
    this.auditEventRecorder = auditEventRecorder;
    this.clock = clock;
  }

  /**
   * @return how many releases this run took out of effect
   */
  @Transactional
  public int runOnce() {
    Instant now = clock.instant();
    List<KnowledgeLibrary> due =
        libraryRepository.findByExternalAccessStateAndExternalAccessExpiresAtLessThanEqual(
            ExternalAccessState.ACTIVE, now);
    for (KnowledgeLibrary library : due) {
      Instant ranTo = library.getExternalAccessExpiresAt();
      library.expireExternalAccess();
      KnowledgeLibrary saved = libraryRepository.save(library);
      permissionHistoryService.recordExternalAccessChanged(
          saved, LibraryVisibilityHistoryCause.EXTERNAL_ACCESS_EXPIRED, null);
      auditEventRecorder.recordSystemProcessAction(
          AuditEvent.builder()
              .organizationId(saved.getOrganizationId())
              .actorRef(EXTERNAL_ACCESS_ACTOR)
              .type(AuditEventType.ASSET_EXTERNAL_ACCESS_EXPIRED)
              .object(AuditObjectType.KNOWLEDGE_LIBRARY, saved.getId(), saved.getName())
              .before(Map.of("externalAccess", ExternalAccessState.ACTIVE.name()))
              .after(
                  Map.of(
                      "externalAccess",
                      ExternalAccessState.EXPIRED.name(),
                      "cause",
                      "RELEASE_PERIOD_ELAPSED",
                      "expiresAt",
                      ranTo.toString()))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    if (!due.isEmpty()) {
      log.info("External access release expired for {} libraries", due.size());
    }
    return due.size();
  }
}
