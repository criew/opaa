package io.opaa.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.DocumentService;
import io.opaa.indexing.document.DocumentTextRecognitionStore.UploadBacklog;
import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DocumentTextRecognitionStore#uploadBacklog} against the real schema: it counts only
 * uploaded scans still waiting for text recognition - not a scan from a source, not an upload that
 * failed for another reason - and gives the age of the oldest, {@code 0} without any. Runs against
 * a temporary copy of {@code documents} that shadows the shared one within the transaction, so no
 * row of another test is seen or touched.
 */
@OpaaIntegrationTest
class TextRecognitionUploadBacklogIntegrationTest {

  private static final int PIPELINE = 1;
  private static final String CONFIGURATION = "ocr-test";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  void onlyUploadedScansStillWaitingCountAndTheOldestGivesTheAge() {
    transactionTemplate.executeWithoutResult(
        status -> {
          status.setRollbackOnly();
          jdbcTemplate.execute(
              "CREATE TEMPORARY TABLE documents (LIKE documents INCLUDING DEFAULTS)"
                  + " ON COMMIT DROP");
          DocumentTextRecognitionStore store = new DocumentTextRecognitionStore(jdbcTemplate);

          assertThat(store.uploadBacklog(PIPELINE, CONFIGURATION))
              .isEqualTo(new UploadBacklog(0, 0.0));

          insert("UPLOAD", DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE, "interval '3 hours'");
          insert("UPLOAD", DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE, "interval '1 minute'");
          insert("FILESYSTEM", DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE, "interval '9 hours'");
          insert("UPLOAD", "parser crashed", "interval '9 hours'");

          UploadBacklog backlog = store.uploadBacklog(PIPELINE, CONFIGURATION);
          assertThat(backlog.waiting()).isEqualTo(2);
          assertThat(backlog.oldestAgeSeconds()).isBetween(10_800.0, 10_800.0 + 600);
        });
  }

  private void insert(String sourceType, String error, String age) {
    jdbcTemplate.update(
        "INSERT INTO documents (file_name, file_path, content_type, status, source_type,"
            + " library_id, organization_id, error_message, created_at) VALUES ('scan.pdf',"
            + " '/scan.pdf', ?, 'FAILED', ?, gen_random_uuid(), gen_random_uuid(), ?, now() - "
            + age
            + ")",
        DocumentTextRecognitionStore.PDF_CONTENT_TYPE,
        sourceType,
        error);
  }
}
