package io.opaa.library;

import io.opaa.common.ProcessStart;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.UploadedOriginalStore;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Recovers uploads left stuck at {@code PENDING} by a process that died mid-{@code
 * uploadTaskExecutor} task. {@code DocumentIngestService#processUploadedFileAsync} always leaves a
 * row at either {@code INDEXED} or {@code FAILED} when it runs to completion - but if the JVM stops
 * before that (a crash, an operator-initiated restart, an out-of-memory kill), the in-flight task
 * vanishes with it, since the executor's queue lives only in memory. Without this recovery, such a
 * row would poll {@code PENDING} forever in the frontend, and a retry of the same file would answer
 * {@code 409} because only a {@code FAILED} row is replaced by a new upload ({@code
 * LibraryDocumentService#uploadDocument}).
 *
 * <p>Runs once at application startup, when the web server already accepts uploads. The cutoff is
 * therefore the {@link ProcessStart}, not an age: every {@code PENDING} upload created before it
 * belonged to an earlier process and is failed, however young; one created after it has a task of
 * this process behind it and is left alone (ADR-0021).
 */
@Component
public class UploadPendingRecoveryRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(UploadPendingRecoveryRunner.class);

  static final String FAILURE_REASON = "Die Verarbeitung wurde durch einen Neustart abgebrochen";

  private final DocumentRepository documentRepository;
  private final UploadedOriginalStore uploadedOriginalStore;
  private final ProcessStart processStart;

  public UploadPendingRecoveryRunner(
      DocumentRepository documentRepository,
      UploadedOriginalStore uploadedOriginalStore,
      ProcessStart processStart) {
    this.documentRepository = documentRepository;
    this.uploadedOriginalStore = uploadedOriginalStore;
    this.processStart = processStart;
  }

  /**
   * The rows first, then the store's own leftovers ({@link
   * UploadedOriginalStore#recoverAfterRestart}): both are what a process that died mid-task leaves
   * behind (ADR-0030, Entscheidung 7).
   */
  @Override
  public void run(ApplicationArguments args) {
    recover(processStart.instant());
  }

  /** Fails every {@code PENDING} upload created before {@code processStarted}. */
  void recover(Instant processStarted) {
    int recovered =
        documentRepository.failPendingUploadsCreatedBefore(FAILURE_REASON, processStarted);
    if (recovered > 0) {
      log.warn(
          "Marked {} upload(s) left PENDING by the previous process as FAILED on startup",
          recovered);
    }
    uploadedOriginalStore.recoverAfterRestart();
  }
}
