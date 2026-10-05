package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.asset.AssetShellService;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.connection.PrivateLibraryConnections;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.ErasedLibraryReferences;
import io.opaa.knowledge.ErasedLibraryReferences.ErasedLibrary;
import io.opaa.knowledge.ErasureCause;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.knowledge.LibraryFolderRepository;
import io.opaa.knowledge.UploadedOriginalRef;
import io.opaa.knowledge.UploadedOriginalStore;
import io.opaa.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one erasure of a private library, for its owner's "Sofort löschen" and for the deletion run
 * (#2165). It first commits the marker, so no run starts and a running one ends at its next access
 * to the secret; while a run is still going it answers {@link Outcome#PENDING} and the next call
 * continues. Then, in one transaction, it deletes content, runs, references and the library, checks
 * that nothing of it is left and records the proof {@code PRIVATE_LIBRARY_ERASED} - or rolls back,
 * the marker staying for the next call. Repeatable: a library already gone is {@link
 * Outcome#ERASED}. Logs and proof name ids only, never a name or path; the proof tells of each kind
 * of content only whether there was any ({@link #NONE}/{@link #PRESENT}), the checks of what is
 * left stay exact.
 */
@Service
public class PrivateLibraryErasure {

  static final String DELETION_RUN_ACTOR = "private-library-deletion";

  /** The proof's level of a kind of content the erasure found none of. */
  static final String NONE = "NONE";

  /** The proof's level of a kind of content the erasure found and removed. */
  static final String PRESENT = "PRESENT";

  private static final Logger log = LoggerFactory.getLogger(PrivateLibraryErasure.class);

  /** Whether the library is gone or its erasure waits for a running run to end. */
  public enum Outcome {
    ERASED,
    PENDING
  }

  private final KnowledgeLibraryRepository libraries;
  private final DocumentRepository documents;
  private final LibraryFolderRepository folders;
  private final IndexingJobRepository jobs;
  private final SourceSyncStateRepository syncStates;
  private final VectorChunkStore chunks;
  private final UploadedOriginalStore originals;
  private final List<ErasedLibraryReferences> references;
  private final NotificationService notifications;
  private final AssetShellService shellService;
  private final AuditEventRecorder audit;
  private final PrivateLibraryConnections privateConnections;
  private final LibraryAccessService accessService;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public PrivateLibraryErasure(
      KnowledgeLibraryRepository libraries,
      DocumentRepository documents,
      LibraryFolderRepository folders,
      IndexingJobRepository jobs,
      SourceSyncStateRepository syncStates,
      VectorChunkStore chunks,
      UploadedOriginalStore originals,
      List<ErasedLibraryReferences> references,
      NotificationService notifications,
      AssetShellService shellService,
      AuditEventRecorder audit,
      PrivateLibraryConnections privateConnections,
      LibraryAccessService accessService,
      ApplicationEventPublisher events,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.libraries = libraries;
    this.documents = documents;
    this.folders = folders;
    this.jobs = jobs;
    this.syncStates = syncStates;
    this.chunks = chunks;
    this.originals = originals;
    this.references = List.copyOf(references);
    this.notifications = notifications;
    this.shellService = shellService;
    this.audit = audit;
    this.privateConnections = privateConnections;
    this.accessService = accessService;
    this.events = events;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * The owner's "Sofort löschen": empty for a library that is not private, which the ordinary
   * deletion handles. Anyone but its owner - the system administration included - gets the {@code
   * 404} of a library that does not exist.
   */
  public Optional<Outcome> eraseOwn(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library =
        transactions.execute(
            status ->
                libraries
                    .findById(libraryId)
                    .filter(found -> found.getOrganizationId().equals(caller.organizationId()))
                    .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden")));
    if (!library.isOwnerOnly()) {
      return Optional.empty();
    }
    accessService.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.OWNER);
    return Optional.of(erase(libraryId, ErasureCause.OWNER_REQUEST, caller.id()));
  }

  /**
   * Erases private library {@code libraryId} for {@code cause}, by {@code actorUserId} or - {@code
   * null} - by the deletion run. Called outside a transaction: the marker must commit on its own.
   *
   * @throws IllegalStateException inside a transaction, and when something of the library is left
   *     after the deletion; the marker stays and the next call continues
   */
  public Outcome erase(UUID libraryId, ErasureCause cause, UUID actorUserId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("A private library is erased outside a transaction");
    }
    Instant now = clock.instant();
    KnowledgeLibrary marked =
        transactions.execute(
            status -> {
              Optional<KnowledgeLibrary> found = libraries.findById(libraryId);
              if (found.isEmpty()) {
                return null;
              }
              if (!found.get().isOwnerOnly()) {
                throw new IllegalArgumentException(
                    "Only a private library is erased: " + libraryId);
              }
              libraries.requestErasure(libraryId, now, cause.name());
              return libraries.findById(libraryId).orElse(null);
            });
    if (marked == null) {
      return Outcome.ERASED;
    }
    if (running(marked)) {
      log.info("Erasure of private library {} waits for its running indexing run", libraryId);
      return Outcome.PENDING;
    }
    int originalsRemoved = eraseOriginals(marked);
    Outcome outcome =
        transactions.execute(status -> eraseMarked(libraryId, actorUserId, originalsRemoved));
    if (outcome == Outcome.ERASED) {
      log.info("Private library {} erased ({})", libraryId, marked.getErasureCause());
    }
    return outcome;
  }

  private boolean running(KnowledgeLibrary library) {
    return jobs.existsByStatusAndLibraryIdAndOrganizationId(
        JobStatus.RUNNING, library.getId(), library.getOrganizationId());
  }

  /**
   * Removes every stored original of the library's storage area and checks that none is left; a
   * store that cannot be reached throws, and the next call tries again.
   */
  private int eraseOriginals(KnowledgeLibrary library) {
    AtomicInteger removed = new AtomicInteger();
    originals.forEachStoredOriginal(
        library.getOrganizationId(),
        library.getId(),
        original -> {
          originals.delete(
              new UploadedOriginalRef(
                  library.getOrganizationId(), library.getId(), original.locator()));
          removed.incrementAndGet();
        });
    AtomicInteger left = new AtomicInteger();
    originals.forEachStoredOriginal(
        library.getOrganizationId(), library.getId(), original -> left.incrementAndGet());
    if (left.get() > 0) {
      throw new IllegalStateException(
          left.get() + " original(s) of private library " + library.getId() + " are left");
    }
    return removed.get();
  }

  private Outcome eraseMarked(UUID libraryId, UUID actorUserId, int originalsRemoved) {
    Optional<KnowledgeLibrary> locked = libraries.findForErasure(libraryId);
    if (locked.isEmpty()) {
      return Outcome.ERASED;
    }
    KnowledgeLibrary library = locked.get();
    if (running(library)) {
      return Outcome.PENDING;
    }
    UUID organizationId = library.getOrganizationId();
    List<UUID> documentIds = documents.findIdsByLibraryId(libraryId);
    List<UUID> jobIds = new ArrayList<>(jobs.findIdsByLibraryId(libraryId));
    ErasedLibrary erased = new ErasedLibrary(organizationId, libraryId, List.copyOf(documentIds));

    Map<String, Object> counts = new LinkedHashMap<>();
    counts.put("chunksRemoved", chunks.eraseAllOf(libraryId, documentIds));
    for (ErasedLibraryReferences holder : references) {
      counts.put(holder.countKey(), holder.remove(erased));
    }
    counts.put("documentsRemoved", documents.deleteByLibraryId(libraryId));
    counts.put("foldersRemoved", folders.countByLibraryId(libraryId));
    jobs.deleteAllByIdInBatch(jobIds);
    counts.put("runsRemoved", jobIds.size());
    counts.put("originalsRemoved", originalsRemoved);
    notifications.deleteAbout(libraryId);

    shellService.registerDeleted(library, actorUserId);
    recordProof(library, actorUserId, counts);
    UUID owner = library.getOwnerUserId();
    libraries.delete(library);
    libraries.flush();
    privateConnections.afterErasure(owner);

    requireNothingLeft(erased, jobIds);
    events.publishEvent(new PrivateLibraryErased(organizationId, libraryId, owner));
    return Outcome.ERASED;
  }

  private void recordProof(KnowledgeLibrary library, UUID actorUserId, Map<String, Object> counts) {
    Map<String, Object> proof = new LinkedHashMap<>();
    proof.put("cause", library.getErasureCause().name());
    proof.put("requestedAt", library.getErasureRequestedAt().toString());
    proof.put("sourceType", library.getSourceType().key());
    proof.put("erasedCompletely", true);
    counts.forEach((key, count) -> proof.put(key, level(count)));
    AuditEvent.Builder entry =
        AuditEvent.builder()
            .organizationId(library.getOrganizationId())
            .type(AuditEventType.PRIVATE_LIBRARY_ERASED)
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, library.getId(), library.auditName())
            .before(library.auditPayload(proof))
            .outcome(AuditOutcome.SUCCESS);
    if (actorUserId == null) {
      audit.recordSystemProcessAction(entry.actorRef(DELETION_RUN_ACTOR).build());
    } else {
      audit.recordUserAction(entry.actor(actorUserId).build());
    }
  }

  /** A count as the proof keeps it: whether there was any, never how many. */
  private static String level(Object count) {
    return count instanceof Number number && number.longValue() > 0 ? PRESENT : NONE;
  }

  /** The checks of every place the library had; one that is not empty rolls the erasure back. */
  private void requireNothingLeft(ErasedLibrary erased, List<UUID> jobIds) {
    UUID libraryId = erased.libraryId();
    Map<String, Long> left = new LinkedHashMap<>();
    left.put("libraries", libraries.existsById(libraryId) ? 1L : 0L);
    left.put("documents", documents.countByLibraryId(libraryId));
    left.put("chunks", chunks.remainingOf(libraryId, erased.documentIds()));
    left.put("folders", folders.countByLibraryId(libraryId));
    left.put("runs", jobIds.isEmpty() ? 0L : jobs.countByIdIn(jobIds));
    left.put("syncStates", syncStates.countByLibraryId(libraryId));
    left.put("notifications", notifications.countAbout(libraryId));
    for (ErasedLibraryReferences holder : references) {
      left.put(holder.countKey(), holder.remaining(erased));
    }
    left.values().removeIf(count -> count == 0);
    if (!left.isEmpty()) {
      throw new IllegalStateException(
          "Erasure of private library " + libraryId + " left rows behind: " + left);
    }
  }
}
