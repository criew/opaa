package io.opaa.indexing.format.file.fallback;

import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.document.DocumentService;
import io.opaa.indexing.format.ChunkFormatMetadata;
import io.opaa.indexing.format.DocumentFormat;
import io.opaa.indexing.format.DocumentFormatResult;
import io.opaa.indexing.format.DocumentFormatSource;
import io.opaa.indexing.format.DocumentProperties;
import io.opaa.indexing.format.FormatAdmission;
import io.opaa.indexing.format.shared.DocumentTitleLine;
import io.opaa.indexing.format.shared.SetextHeading;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * The universal fallback pipeline (docs/features/ingestion-pipelines.md, Teil 1): Tika reader plus
 * the token splitter with the globally configured {@code opaa.indexing.chunk-size}/{@code
 * -overlap}. It claims no format ({@link #handledFormats()} is empty) and handles everything no
 * specialized pipeline claimed.
 *
 * <p>Its chunk size is <b>gesetzt, nicht gemessen</b> (ingestion-pipelines.md, "Chunk-Größen"): the
 * 1000/100-token default {@code ChunkingService} applies globally, not a value measured against a
 * verwaltungs-corpus.
 */
public class TikaFallbackFormat implements DocumentFormat {

  private static final Logger log = LoggerFactory.getLogger(TikaFallbackFormat.class);

  /**
   * The persisted identity of this format, declared in {@link ChunkFormatMetadata} because the
   * Bestand from before the format abstraction is attributed to it (see {@link
   * ChunkFormatMetadata#LEGACY_PIPELINE_ID}).
   */
  public static final String ID = ChunkFormatMetadata.LEGACY_PIPELINE_ID;

  /**
   * Chunks carrying no pipeline metadata at all are attributed to {@link
   * ChunkFormatMetadata#LEGACY_PIPELINE_VERSION}, never to this version.
   */
  public static final short VERSION = 1;

  private final DocumentService documentService;
  private final ChunkingService chunkingService;

  public TikaFallbackFormat(DocumentService documentService, ChunkingService chunkingService) {
    this.documentService = documentService;
    this.chunkingService = chunkingService;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public short version() {
    return VERSION;
  }

  /**
   * The formats this system accepts without a specialized pipeline for them - a named decision
   * rather than a leftover, and the only reason {@code .txt} and {@code .doc} are admitted at all.
   * A future format taking one of them over takes it out of this declaration; two formats admitting
   * the same extension fail at context startup.
   *
   * <p>{@code .txt} is text-tolerant (plain text is what it is); {@code .doc} is matched strictly
   * against {@code application/msword} and deliberately not against {@code
   * application/x-tika-msoffice}, the generic OLE2 container type Tika falls back to when POI's
   * sniffing inside the container fails - any unidentifiable OLE2 file would otherwise pass as a
   * "matching" DOC.
   */
  @Override
  public Set<FormatAdmission> admittedFormats() {
    return Set.of(
        FormatAdmission.textTolerant(".txt", "text/plain"),
        FormatAdmission.detectedAs(".doc", "application/msword"));
  }

  /**
   * Empty although this format admits {@code .txt} and {@code .doc}: it claims no format for
   * routing and handles everything no specialized pipeline claimed, which is the same thing for an
   * extension nobody else claims and the reason the two are not derived from each other here.
   */
  @Override
  public Set<String> handledFormats() {
    return Set.of();
  }

  @Override
  public DocumentFormatResult run(DocumentFormatSource source) {
    List<Document> parsed;
    if (source.file() != null) {
      parsed = documentService.parseDocument(source.file());
      if (parsed.isEmpty()) {
        return DocumentFormatResult.noContent();
      }
    } else {
      parsed = List.of(new Document(source.extractedText()));
    }

    List<Document> chunks = chunkingService.chunkDocuments(source.fileName(), parsed);
    if (chunks.isEmpty()) {
      // Non-blank parsed text can still chunk down to nothing (OCR noise or page footers below
      // ChunkingService's own minChunkLengthToEmbed/minChunkSizeChars) - reported as a rejection,
      // so no caller can end up INDEXED with zero chunks.
      return DocumentFormatResult.noExtractableText();
    }
    return DocumentFormatResult.chunked(chunks).withProperties(properties(source, parsed));
  }

  /**
   * The opening of the extracted text - the only metadata source this pipeline has, since Tika's
   * own document properties are not read here. Parses the whole document for it; Tika has no
   * cheaper entry point, and the formats reaching this pipeline are small.
   */
  @Override
  public DocumentProperties readProperties(DocumentFormatSource source) {
    if (source.file() == null) {
      return DocumentProperties.EMPTY;
    }
    try {
      return properties(source, documentService.parseDocument(source.file()));
    } catch (RuntimeException e) {
      log.warn("Could not read properties of {} via Tika", source.fileName(), e);
      return DocumentProperties.EMPTY;
    }
  }

  /**
   * Title line, head text and - for the one heading notation a {@code .txt} has - a leading Setext
   * heading. All three are a file's alone: text without a file was extracted upstream and is a feed
   * entry, which names other documents than itself.
   */
  private static DocumentProperties properties(DocumentFormatSource source, List<Document> parsed) {
    if (source.file() == null) {
      return DocumentProperties.EMPTY;
    }
    String headText = headText(parsed);
    return DocumentProperties.EMPTY
        .withTitleLine(DocumentTitleLine.of(headText))
        .withFirstHeading(SetextHeading.leadingOf(headText))
        .withHeadText(headText);
  }

  /**
   * The opening of the parsed text, taken across the parsed documents in order and stopping at
   * {@link DocumentProperties#MAX_HEAD_TEXT_LENGTH} characters - a single document of the parse can
   * be megabytes, so only what fits is copied.
   */
  private static String headText(List<Document> parsed) {
    StringBuilder head = new StringBuilder();
    for (Document document : parsed) {
      int remaining = DocumentProperties.MAX_HEAD_TEXT_LENGTH - head.length();
      if (remaining <= 0) {
        break;
      }
      String text = document.getText();
      if (text == null || text.isBlank()) {
        continue;
      }
      if (head.length() > 0) {
        head.append('\n');
        remaining--;
      }
      head.append(text, 0, Math.min(text.length(), remaining));
    }
    return head.length() == 0 ? null : head.toString();
  }
}
