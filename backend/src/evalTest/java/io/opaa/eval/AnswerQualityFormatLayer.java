package io.opaa.eval;

import io.opaa.format.DocumentFormat;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.file.markdown.MarkdownDocumentFormat;
import io.opaa.format.file.office.DocxDocumentFormat;
import io.opaa.format.file.pdf.PdfDocumentFormat;
import io.opaa.format.file.tabular.TabularDocumentFormat;
import io.opaa.format.file.tabular.TabularProperties;
import io.opaa.format.shared.HeadingSectionSplitter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * The Docker-free layer of the answer-quality domain: what the production formats cut out of the
 * corpus files, read as the passages the answer prompt could hold at best. A case is solved on this
 * layer when one chunk of its document carries all of its evidence (the value with its unit, or
 * with its row and column). That is a necessary condition for a correct answer, not a sufficient
 * one: the layers after it (embedding, search, neighbour expansion) can still lose the chunk.
 */
final class AnswerQualityFormatLayer {

  private final Path corpusDir;
  private final HeadingSectionSplitter splitter;
  private final Map<String, List<String>> chunkTexts = new LinkedHashMap<>();

  AnswerQualityFormatLayer(Path corpusDir, HeadingSectionSplitter splitter) {
    this.corpusDir = corpusDir;
    this.splitter = splitter;
  }

  /** The chunk texts the production format of {@code fileName}'s extension produces. */
  List<String> chunks(String fileName) {
    return chunkTexts.computeIfAbsent(fileName, this::cut);
  }

  private List<String> cut(String fileName) {
    String extension = fileName.substring(fileName.lastIndexOf('.'));
    DocumentFormat format =
        switch (extension) {
          case ".pdf" -> new PdfDocumentFormat(splitter);
          case ".docx" -> new DocxDocumentFormat(splitter);
          case ".xlsx" -> new TabularDocumentFormat(new TabularProperties(0, 0, 0, 0), splitter);
          default -> new MarkdownDocumentFormat(splitter);
        };
    DocumentFormatResult result =
        format.run(DocumentFormatSource.ofFile(corpusDir.resolve(fileName), fileName, extension));
    if (result.outcome() != DocumentFormatResult.Outcome.CHUNKED) {
      throw new IllegalStateException(fileName + " was not chunked: " + result.outcome());
    }
    return result.chunks().stream().map(Document::getText).toList();
  }

  /**
   * Whether one chunk of one of the case's documents carries all of its evidence and none of the
   * case's table documents is read under a wrong header (the title row taken for the header, or the
   * last row without it).
   */
  boolean evidenceReaches(AnswerQualityCase c, List<AnswerQualityCase.TableCheck> tableChecks) {
    boolean evidence =
        c.documents().stream()
            .anyMatch(
                document -> AnswerQualityCase.evidenceInOnePassage(chunks(document), c.evidence()));
    boolean headerRight =
        tableChecks.stream()
            .filter(check -> c.documents().contains(check.document()))
            .allMatch(check -> lastRowCarriesHeader(check) && !titleTakenAsHeader(check));
    return evidence && headerRight;
  }

  /**
   * Whether the chunk of the table's last data row repeats the title row as if it were the header.
   */
  boolean titleTakenAsHeader(AnswerQualityCase.TableCheck check) {
    if (check.title() == null) {
      return false;
    }
    String title = AnswerQualityCase.normalize(check.title());
    String lastRow = AnswerQualityCase.normalize(check.lastRow());
    return chunks(check.document()).stream()
        .map(AnswerQualityCase::normalize)
        .anyMatch(chunk -> chunk.contains(lastRow) && chunk.contains(title));
  }

  /** Whether the chunk of the table's last data row carries every header cell. */
  boolean lastRowCarriesHeader(AnswerQualityCase.TableCheck check) {
    String lastRow = AnswerQualityCase.normalize(check.lastRow());
    return chunks(check.document()).stream()
        .map(AnswerQualityCase::normalize)
        .filter(chunk -> chunk.contains(lastRow))
        .anyMatch(
            chunk ->
                check.headerCells().stream()
                    .map(AnswerQualityCase::normalize)
                    .allMatch(chunk::contains));
  }
}
