package io.opaa.format.shared;

import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ceilings the structure formats (PDF, DOCX, PPTX, XLSX/CSV, HTML, Markdown) apply to one document,
 * so that a crafted file cannot cost more than bounded memory, CPU time and embedding work.
 * ODT/ODP/ODS keep their own blocks ({@code OdfProperties}, {@code TabularProperties}). A document
 * past any of them fails to parse as a whole; nothing is truncated.
 *
 * @param maxTextCharacters the most characters one document's chunks may carry in total. The PDF
 *     reader counts glyphs while it extracts, so a page that draws the same form thousands of times
 *     stops before its text reaches the heap; XLSX counts cell text row by row. For HTML, Markdown
 *     and CSV the text cannot outgrow the file, which is already in memory, so there the limit
 *     bounds the number of chunks and with it the embedding work, not the heap. Default 10 000 000.
 * @param maxOoxmlPartBytes the most bytes a single XML part of a DOCX/PPTX/XLSX package may unpack
 *     to, measured before POI builds its DOM. Default 32 MiB.
 * @param maxOoxmlTotalBytes the most bytes all XML parts of one DOCX/PPTX/XLSX package may unpack
 *     to together. A part whose first bytes cannot start an XML document (an image, an embedded
 *     file) does not count. Default 64 MiB.
 * @param maxPdfOperators the most content-stream operators PDFBox may process for one PDF, over all
 *     pages, nested forms and reading passes; every element of an array operand counts as one more.
 *     Bounds the work of forms nested many levels deep that multiply it without drawing any text.
 *     Default 200 000 000.
 * @param maxPdfContentBytes the most decoded content-stream bytes PDFBox may parse for one PDF:
 *     every page's content and every form, each time it is drawn, over all reading passes. Bounds
 *     the parsing work of a large form drawn many times. Default 512 MiB.
 * @param maxPdfParseSeconds the wall-clock time one PDF may take to read, as a backstop for work
 *     the counts above do not see. Default 120.
 */
@PerInstance("bounds of a parser of this process")
@NoDataImpact("parser bounds")
@ConfigurationProperties(prefix = "opaa.indexing.parser-limits")
public record ParserLimitProperties(
    long maxTextCharacters,
    long maxOoxmlPartBytes,
    long maxOoxmlTotalBytes,
    long maxPdfOperators,
    long maxPdfContentBytes,
    long maxPdfParseSeconds) {

  /** The defaults, for formats constructed without configuration (tests, single-format use). */
  public static final ParserLimitProperties DEFAULTS = new ParserLimitProperties(0, 0, 0, 0, 0, 0);

  public ParserLimitProperties {
    maxTextCharacters = positiveOr(maxTextCharacters, 10_000_000L);
    maxOoxmlPartBytes = positiveOr(maxOoxmlPartBytes, 33_554_432L); // 32 MiB
    maxOoxmlTotalBytes = positiveOr(maxOoxmlTotalBytes, 67_108_864L); // 64 MiB
    maxPdfOperators = positiveOr(maxPdfOperators, 200_000_000L);
    maxPdfContentBytes = positiveOr(maxPdfContentBytes, 536_870_912L); // 512 MiB
    maxPdfParseSeconds = positiveOr(maxPdfParseSeconds, 120L);
  }

  /**
   * Throws {@link ParserLimitExceededException} when {@code chunks} carry more than {@link
   * #maxTextCharacters} characters together; {@code subject} names the format in the message.
   */
  public void requireTextWithinLimit(List<Document> chunks, String subject) {
    long total = 0;
    for (Document chunk : chunks) {
      String text = chunk.getText();
      total += text == null ? 0 : text.length();
      if (total > maxTextCharacters) {
        throw ParserLimitExceededException.textCharacters(subject, maxTextCharacters);
      }
    }
  }
}
