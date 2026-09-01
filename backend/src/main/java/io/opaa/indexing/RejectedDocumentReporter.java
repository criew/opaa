package io.opaa.indexing;

import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.filesystem.AsyncIndexingExecutor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports documents an indexing run rejected because of an unsupported format: rejected documents
 * are part of the job, not invisible - they count towards the total and are reported as skipped, so
 * nobody has to guess why the number of indexed documents is lower than the number of items the
 * source offered.
 *
 * <p>Used by {@link AsyncIndexingExecutor}, which maps its own rejected-item list down to display
 * names before calling this shared helper. Public - consumed from the {@code source.filesystem}
 * package (#1113); still not part of any cross-module API surface.
 */
public final class RejectedDocumentReporter {

  private static final Logger log = LoggerFactory.getLogger(RejectedDocumentReporter.class);

  private RejectedDocumentReporter() {}

  /**
   * Logs every rejected document name, naming the run's {@code sourceType} and {@code location}
   * (directory path or URL), and returns how many there were.
   */
  public static int reportRejected(
      IndexingSourceType sourceType, String location, List<String> rejectedNames) {
    if (rejectedNames.isEmpty()) {
      return 0;
    }
    log.warn(
        "[{}] Rejected {} document(s) from {} because of an unsupported format (supported: {}):"
            + " {}",
        sourceType,
        rejectedNames.size(),
        location,
        SupportedDocumentFormats.extensions(),
        rejectedNames);
    return rejectedNames.size();
  }
}
