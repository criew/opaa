package io.opaa.format.shared;

import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ceilings the structure formats (PDF, DOCX, PPTX, XLSX/CSV, HTML, Markdown) apply to one document,
 * so that a crafted file cannot cost more than bounded memory and embedding work. ODT/ODP/ODS keep
 * their own blocks ({@code OdfProperties}, {@code TabularProperties}). A document past any of them
 * fails to parse as a whole; nothing is truncated.
 *
 * @param maxTextCharacters the most characters one document's chunks may carry in total. The PDF
 *     reader counts glyphs while it extracts, so a page that draws the same form thousands of times
 *     stops before its text reaches the heap. Default 10 000 000.
 * @param maxOoxmlPartBytes the most bytes a single XML part of a DOCX/PPTX/XLSX package may unpack
 *     to, measured before POI builds its DOM. Default 32 MiB.
 * @param maxOoxmlTotalBytes the most bytes all XML parts of one DOCX/PPTX/XLSX package may unpack
 *     to together. Parts POI reads only on demand (images, embedded objects) do not count. Default
 *     64 MiB.
 */
@PerInstance("bounds of a parser of this process")
@NoDataImpact("parser bounds")
@ConfigurationProperties(prefix = "opaa.indexing.parser-limits")
public record ParserLimitProperties(
    long maxTextCharacters, long maxOoxmlPartBytes, long maxOoxmlTotalBytes) {

  /** The defaults, for formats constructed without configuration (tests, single-format use). */
  public static final ParserLimitProperties DEFAULTS = new ParserLimitProperties(0, 0, 0);

  public ParserLimitProperties {
    maxTextCharacters = positiveOr(maxTextCharacters, 10_000_000L);
    maxOoxmlPartBytes = positiveOr(maxOoxmlPartBytes, 33_554_432L); // 32 MiB
    maxOoxmlTotalBytes = positiveOr(maxOoxmlTotalBytes, 67_108_864L); // 64 MiB
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
