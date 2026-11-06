package io.opaa.indexing.document;

import io.opaa.format.DocumentFormat;
import io.opaa.format.DocumentFormatResult;
import io.opaa.indexing.job.IndexingEventSink;
import io.opaa.sourceaccess.LoggedName;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * The hook of {@link DocumentIngestService} for a PDF the {@code pdf} format rejected as without
 * extractable text: text recognition (OCR) may turn it into chunks. An empty answer keeps today's
 * rejection unchanged - always while text recognition is switched off and nothing is stored for the
 * document.
 */
public interface ScannedDocumentRecognition {

  /** Never recognises anything: the indexing behaves as without text recognition. */
  ScannedDocumentRecognition NONE = candidate -> Optional.empty();

  /**
   * The chunks of {@code candidate}, cut by {@code pipeline}; empty to keep the rejection. Records
   * its own protocol entries in {@link Candidate#events()}.
   */
  Optional<Recognized> recognize(Candidate candidate);

  /**
   * A scanned PDF.
   *
   * @param checksum the file's checksum, which stored recognition and lock are bound to
   * @param reference what the protocol names the document by, its {@code file_path}
   * @param events the run protocol, {@code null} without one
   * @param reindex a re-index of unchanged content: only a stored recognition is used, the service
   *     is never called
   */
  record Candidate(
      UUID documentId,
      String checksum,
      Path file,
      String reference,
      LoggedName names,
      IndexingEventSink events,
      boolean reindex) {}

  /** The chunks of a recognised scan and the format that cut them. */
  record Recognized(DocumentFormatResult result, DocumentFormat pipeline) {}
}
