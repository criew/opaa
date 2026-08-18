package io.opaa.indexing;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports documents an indexing run rejected because of an unsupported format (issue #375):
 * rejected documents are part of the job, not invisible - they count towards the total and are
 * reported as skipped, so nobody has to guess why the number of indexed documents is lower than the
 * number of items the source offered.
 *
 * <p>Both {@link AsyncIndexingExecutor} and {@link UrlIndexingExecutor} used to duplicate this
 * exact logic against different element types ({@code Path} vs. {@code
 * AutoindexCrawlerService.CrawledFileEntry}, see ADR-0017). Each executor now maps its own
 * rejected-item list down to display names before calling this shared helper, keeping the reporting
 * itself in exactly one place. {@code sourceType} and {@code location} are passed in explicitly - a
 * single shared logger name would otherwise erase the run type/origin the two executors' own
 * loggers used to convey implicitly.
 */
final class RejectedDocumentReporter {

  private static final Logger log = LoggerFactory.getLogger(RejectedDocumentReporter.class);

  private RejectedDocumentReporter() {}

  /**
   * Logs every rejected document name, naming the run's {@code sourceType} and {@code location}
   * (directory path or URL), and returns how many there were.
   */
  static int reportRejected(
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
