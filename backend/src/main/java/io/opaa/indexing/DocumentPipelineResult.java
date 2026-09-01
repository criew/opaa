package io.opaa.indexing;

import java.util.List;
import org.springframework.ai.document.Document;

/**
 * What a {@link DocumentPipeline} produced for one document: its chunks, plus the reason there are
 * none when a pipeline could not turn the source into anything indexable.
 *
 * <p>The three outcomes are the ones {@code FileProcessingService} has always distinguished, moved
 * behind the abstraction so that a format-specific pipeline decides them for its own format instead
 * of the caller re-deciding for every format (the open-closed criterion of
 * docs/features/ingestion-pipelines.md, Teil 1): a scan PDF is {@code NO_EXTRACTABLE_TEXT} because
 * the PDF pipeline says so, not because the caller knows about PDFs.
 *
 * @param chunks never {@code null}; empty for every outcome other than {@link Outcome#CHUNKED}
 */
public record DocumentPipelineResult(Outcome outcome, List<Document> chunks) {

  public enum Outcome {
    /** At least one chunk was produced. */
    CHUNKED,
    /**
     * The reader returned nothing at all - the document could not be parsed into any text. Maps to
     * the generic failure, not to the "likely a scan" rejection.
     */
    NO_CONTENT,
    /**
     * The document parsed, but carries no usable text - a PDF without a text layer (see {@link
     * DocumentService#isTextlessPdf}), or text that chunked down to nothing. Maps to {@link
     * DocumentService#NO_EXTRACTABLE_TEXT_MESSAGE}.
     */
    NO_EXTRACTABLE_TEXT
  }

  public DocumentPipelineResult {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
  }

  static DocumentPipelineResult chunked(List<Document> chunks) {
    return new DocumentPipelineResult(Outcome.CHUNKED, chunks);
  }

  static DocumentPipelineResult noContent() {
    return new DocumentPipelineResult(Outcome.NO_CONTENT, List.of());
  }

  static DocumentPipelineResult noExtractableText() {
    return new DocumentPipelineResult(Outcome.NO_EXTRACTABLE_TEXT, List.of());
  }
}
