package io.opaa.indexing.format;

import io.opaa.indexing.chunk.ChunkingService;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One ingestion pipeline (ingestion-pipelines.md, Teil 1): reader, splitter, metadata enrichment
 * and chunk size for one class of documents, behind a single call.
 *
 * <p>Open-closed is the acceptance criterion: adding a format means adding an implementation and
 * registering it as a bean, with nothing in {@link DocumentFormatRegistry}, {@code
 * DocumentIngestService} or {@link SupportedDocumentFormats} changing shape. Admission is declared
 * here too, through {@link #admittedFormats()}: {@link SupportedDocumentFormats} is the union of
 * those declarations over every registered format and keeps no list of its own. Every chunk carries
 * {@link #id()} and {@link #version()}, and {@link #version()} is raised whenever the cut or the
 * emitted structure metadata changes - that is what makes a selective re-index answerable.
 */
public interface DocumentFormat {

  /**
   * Stable identity, persisted on every chunk this pipeline produces - renaming it orphans the
   * existing corpus from its pipeline, so it is part of the persisted contract, not a display name.
   */
  String id();

  /**
   * The version of the cut and structure metadata this pipeline currently produces. Raised on a
   * change to either; never on a behaviour-neutral fix.
   */
  short version();

  /**
   * What this format admits for indexing: one {@link FormatAdmission} per extension, with the media
   * types belonging to it. Empty for a format that never arrives as a file and is named by its
   * source instead (see {@code io.opaa.indexing.format.stream}). Two formats declaring the same
   * extension or the same media type fail at context startup rather than letting bean order decide
   * ({@link SupportedDocumentFormats}); an extension the fallback already admits is therefore taken
   * out of its declaration when a specialized format takes it over.
   */
  default Set<FormatAdmission> admittedFormats() {
    return Set.of();
  }

  /**
   * The extensions this pipeline claims for routing (e.g. {@code ".pdf"}) - the routing key {@link
   * DocumentFormatRegistry} resolves from a document's <em>detected content</em>, never from its
   * file name alone. Derived from {@link #admittedFormats()}, since a format routes what it admits;
   * overridden as empty by the fallback pipeline, which admits extensions without claiming them and
   * handles everything no other pipeline claimed.
   */
  default Set<String> handledFormats() {
    return admittedFormats().stream()
        .map(FormatAdmission::extension)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Parses and splits {@code source} into chunks. A parse failure of {@code source} itself is
   * reported by throwing: {@link DocumentFormatRunner} maps that to {@link
   * DocumentFormatResult.Outcome#PARSE_FAILED} for every format alike, which is why no pipeline
   * carries a catch-and-map block of its own. A pipeline that finds embedded objects without
   * turning them into chunks reports them via {@link DocumentFormatResult#discoveredAttachments()}
   * (ADR-0022) and must honour {@link DocumentFormatSource#attachmentIndex()}; {@link
   * DocumentFormatRunner} owns the temp files.
   */
  DocumentFormatResult run(DocumentFormatSource source);

  /**
   * The raw metadata sources of {@code source} alone (ADR-0024) - what {@link #run} would attach as
   * {@link DocumentFormatResult#properties()}, without chunking. Lets the Bestandslauf re-read a
   * document's core fields from its original file without re-chunking or re-embedding it. Never
   * throws for a parse failure; returns {@link DocumentProperties#EMPTY} instead. Defaults to
   * {@code EMPTY} for a pipeline whose format declares nothing usable.
   */
  default DocumentProperties readProperties(DocumentFormatSource source) {
    return DocumentProperties.EMPTY;
  }

  /**
   * Chunk metadata keys {@code DocumentIngestService#storeChunks} carries onto the persisted chunk
   * - a ceiling, not a promise, and never able to override its own bookkeeping keys. It filters
   * against the union of every registered pipeline's declaration ({@link
   * DocumentFormatRegistry#allPassthroughMetadataKeys()}), so a key only a nested pipeline declares
   * still passes through. Never {@code null}.
   */
  default Set<String> passthroughMetadataKeys() {
    return Set.of(ChunkingService.LOCATION_METADATA_KEY);
  }
}
