package io.opaa.indexing.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A pipeline for a format that only ever arrives as a file: it reads the file exactly once per
 * call, and derives both the chunks and the {@link DocumentProperties} from that one read. A
 * subclass therefore never opens the file itself twice, which is what {@link #run} and {@link
 * #readProperties} did as separate parses before this base existed.
 *
 * <p>A parse failure is reported by throwing out of {@link #read}: {@link #run} lets it propagate
 * to {@link DocumentPipelineRunner}, the single place that maps it to {@link
 * DocumentPipelineResult#parseFailed()} and logs it. {@link #readProperties} answers it with {@link
 * DocumentProperties#EMPTY}, since the Bestandslauf only ever reads supplementary data.
 *
 * @param <T> everything one read yields, as plain data outliving the file handle - a subclass keeps
 *     no open resource in it
 */
public abstract class FileDocumentPipeline<T> implements DocumentPipeline {

  private static final Logger log = LoggerFactory.getLogger(FileDocumentPipeline.class);

  /**
   * Reads {@code source}'s file into everything {@link #chunks} and {@link #properties} need.
   *
   * @throws IOException the file could not be read as this format at all - a parse failure
   */
  protected abstract T read(DocumentPipelineSource source) throws IOException;

  /**
   * The chunks of an already-read document; {@link #run} attaches {@link #properties} to them.
   * {@code source} is passed on for supplementary content only a chunking run needs (an ODF
   * package's {@code styles.xml}), never to re-read what {@link #read} already returned.
   */
  protected abstract DocumentPipelineResult chunks(DocumentPipelineSource source, T content);

  /** What the format itself declares about an already-read document (ADR-0024). */
  protected abstract DocumentProperties properties(T content);

  @Override
  public final DocumentPipelineResult run(DocumentPipelineSource source) {
    if (source.file() == null) {
      // Never reached through the registry - routing to a file format always carries a file, since
      // its detection needs bytes. Defensive rather than a NullPointerException in a subclass.
      return DocumentPipelineResult.parseFailed();
    }
    T content = readUnchecked(source);
    return chunks(source, content).withProperties(properties(content));
  }

  /**
   * What {@link #run} would attach, read from {@code source} alone - by default the same full
   * {@link #read} the chunking run takes. A format overrides this where the Bestandslauf can read
   * its properties more cheaply (a presentation's core properties without building a chunk per
   * slide) or more resiliently (an ODF package's {@code meta.xml}, which is still readable when
   * {@code content.xml} is not).
   *
   * @throws IOException the file could not be read - answered as {@link DocumentProperties#EMPTY}
   */
  protected DocumentProperties declaredProperties(DocumentPipelineSource source)
      throws IOException {
    return properties(read(source));
  }

  @Override
  public final DocumentProperties readProperties(DocumentPipelineSource source) {
    if (source.file() == null) {
      return DocumentProperties.EMPTY;
    }
    try {
      return declaredProperties(source);
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read properties of {} via pipeline {}", source.fileName(), id(), e);
      return DocumentProperties.EMPTY;
    }
  }

  private T readUnchecked(DocumentPipelineSource source) {
    try {
      return read(source);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + source.fileName(), e);
    }
  }
}
