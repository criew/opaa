package io.opaa.library;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import io.opaa.instance.ScheduledRunLocks;
import io.opaa.instance.InstanceRegistry;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.UploadedOriginalStore;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovers uploads left stuck at {@code PENDING} by an instance that died mid-{@code
 * uploadTaskExecutor} task. {@code DocumentIngestService#processUploadedFileAsync} always leaves a
 * row at either {@code INDEXED} or {@code FAILED} when it runs to completion - but if the JVM stops
 * before that (a crash, an operator-initiated restart, an out-of-memory kill), the in-flight task
 * vanishes with it, since the executor's queue lives only in memory. Without this recovery, such a
 * row would poll {@code PENDING} forever in the frontend, and a retry of the same file would answer
 * {@code 409} because only a {@code FAILED} row is replaced by a new upload ({@code
 * LibraryDocumentService#uploadDocument}).
 *
 * <p>An upload is failed once the instance processing it is dead by the instance register, however
 * young the upload (ADR-0053, Entscheidung 8): at startup, and every {@value #SWEEP_MILLIS} ms
 * after on one instance at a time under its scheduler lock. The uploads of a live instance stay, whenever it accepted
 * them.
 */
@Component
public class UploadPendingRecoveryRunner implements ApplicationRunner {

  /** How often the uploads of dead instances are failed, well below {@code dead-after}. */
  static final long SWEEP_MILLIS = 60_000L;

  private static final Logger log = LoggerFactory.getLogger(UploadPendingRecoveryRunner.class);

  static final String FAILURE_REASON =
      "Die Verarbeitung wurde durch einen Neustart oder Ausfall der verarbeitenden Instanz"
          + " abgebrochen";

  private final DocumentRepository documentRepository;
  private final UploadedOriginalStore uploadedOriginalStore;
  private final InstanceRegistry instanceRegistry;

  public UploadPendingRecoveryRunner(
      DocumentRepository documentRepository,
      UploadedOriginalStore uploadedOriginalStore,
      InstanceRegistry instanceRegistry) {
    this.documentRepository = documentRepository;
    this.uploadedOriginalStore = uploadedOriginalStore;
    this.instanceRegistry = instanceRegistry;
  }

  /**
   * The rows first - also those naming no instance, written before it was recorded - then the
   * store's own leftovers ({@link UploadedOriginalStore#recoverAfterRestart}): both are what an
   * instance that died mid-task leaves behind (ADR-0030, Entscheidung 7).
   */
  @Override
  public void run(ApplicationArguments args) {
    int recovered =
        documentRepository.failPendingUploadsWithoutInstance(FAILURE_REASON)
            + failUploadsOfDeadInstances();
    report(recovered);
    uploadedOriginalStore.recoverAfterRestart();
  }

  /**
   * The uploads of instances that died since, without waiting for a restart; their working files
   * wait for the next start of any instance, the tick touches the database only.
   */
  @Scheduled(fixedDelay = SWEEP_MILLIS, initialDelay = SWEEP_MILLIS)
  @SchedulerLock(
      name = "upload-dead-owner-recovery",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_MINUTE,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void recoverUploadsOfDeadInstances() {
    report(failUploadsOfDeadInstances());
  }

  /** A dead instance stays dead, so judging it before the {@code UPDATE} spares every live one. */
  int failUploadsOfDeadInstances() {
    int recovered = 0;
    for (UUID instance : documentRepository.findInstancesProcessingPendingRows()) {
      if (!instanceRegistry.isAlive(instance)) {
        recovered += documentRepository.failPendingUploadsProcessedBy(instance, FAILURE_REASON);
      }
    }
    return recovered;
  }

  private static void report(int recovered) {
    if (recovered > 0) {
      log.warn(
          "Marked {} upload(s) left PENDING by instances that are no longer alive as FAILED",
          recovered);
    }
  }
}
