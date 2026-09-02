package io.opaa.indexing.pipeline.markdown;

import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.HeadingSectionSplitter;
import io.opaa.indexing.pipeline.TikaFallbackPipeline;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;

/**
 * The Markdown pipeline (docs/features/ingestion-pipelines.md, Teil 2: Überschriftenabschnitt,
 * Ebene 1-3). Cuts along ATX headings ({@code # Heading} through {@code ### Heading}) via {@link
 * HeadingSectionSplitter}. A heading line is only recognized outside a fenced code block ({@code
 * ```}), so a commented-out {@code #} line inside an embedded shell snippet is not cut on.
 *
 * <p>A YAML frontmatter block ({@code ---} ... {@code ---}) at the very start of the file, before
 * any heading, is metadata rather than content and is dropped instead of becoming a headingless
 * leading chunk; a {@code ---} anywhere else (e.g. a horizontal rule mid-document, or an
 * unterminated block at the start) is ordinary content. Frontmatter fields themselves are not
 * evaluated here (see #1107 for metadata extraction).
 *
 * <p>Registered as a {@code DocumentPipeline} bean since #1103, replacing {@link
 * TikaFallbackPipeline} for {@code .md}: the retrieval-quality evaluation corpus is entirely
 * Markdown, so this routing change is also a measurement-contract change for the eval domains (see
 * {@code EvalDomainConfig}).
 */
public class MarkdownDocumentPipeline implements DocumentPipeline {

  static final String ID = "markdown";
  static final short VERSION = 1;

  /**
   * Cutting stops at level 3, per the Teil 2 table; a deeper heading folds into its section's text.
   */
  private static final int MAX_CUTTING_LEVEL = 3;

  // Mirrors ChunkLocationResolver's own ATX heading pattern (up to 6 levels recognized; only the
  // first three actually cut, see HeadingSectionSplitter#chunk).
  private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(\\S.*?)[ \\t#]*$");

  private static final Pattern FENCE = Pattern.compile("^ {0,3}(```|~~~)");

  private static final Pattern FRONTMATTER_DELIMITER = Pattern.compile("^-{3}[ \\t]*$");

  @Override
  public String id() {
    return ID;
  }

  @Override
  public short version() {
    return VERSION;
  }

  @Override
  public Set<String> handledFormats() {
    return Set.of(".md");
  }

  @Override
  public DocumentPipelineResult run(DocumentPipelineSource source) {
    String text = stripLeadingFrontmatter(readText(source));
    if (text.isBlank()) {
      return DocumentPipelineResult.noContent();
    }
    List<Document> chunks = HeadingSectionSplitter.chunk(toEvents(text), MAX_CUTTING_LEVEL);
    if (chunks.isEmpty()) {
      return DocumentPipelineResult.noExtractableText();
    }
    return DocumentPipelineResult.chunked(chunks);
  }

  /**
   * Drops a YAML frontmatter block whose opening {@code ---} is the file's very first line, up to
   * and including its closing {@code ---}. A block without a closing delimiter is not frontmatter
   * and is returned unchanged, since discarding it would silently drop the rest of the document.
   */
  private static String stripLeadingFrontmatter(String text) {
    String[] lines = text.split("\n", -1);
    if (lines.length == 0 || !FRONTMATTER_DELIMITER.matcher(stripCr(lines[0])).matches()) {
      return text;
    }
    for (int i = 1; i < lines.length; i++) {
      if (FRONTMATTER_DELIMITER.matcher(stripCr(lines[i])).matches()) {
        return String.join("\n", Arrays.asList(lines).subList(i + 1, lines.length));
      }
    }
    return text;
  }

  private static String stripCr(String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }

  private static String readText(DocumentPipelineSource source) {
    if (source.file() != null) {
      try {
        return Files.readString(source.file(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException("Could not read Markdown document " + source.fileName(), e);
      }
    }
    return source.extractedText();
  }

  /**
   * Splits {@code text} into {@link HeadingSectionSplitter.Event}s: a heading line opens a new
   * section, a fenced code block's contents are kept verbatim as a single paragraph block (never
   * scanned for headings), and every other run of non-blank lines becomes its own paragraph block -
   * a blank line ends the current paragraph, mirroring an ordinary Markdown reader's own paragraph
   * rule.
   */
  private static List<HeadingSectionSplitter.Event> toEvents(String text) {
    List<HeadingSectionSplitter.Event> events = new ArrayList<>();
    StringBuilder paragraph = new StringBuilder();
    boolean inFence = false;
    for (String line : text.split("\n", -1)) {
      String withoutCr = stripCr(line);
      if (FENCE.matcher(withoutCr).find()) {
        inFence = !inFence;
        appendLine(paragraph, withoutCr);
        continue;
      }
      if (inFence) {
        appendLine(paragraph, withoutCr);
        continue;
      }
      Matcher headingMatch = HEADING.matcher(withoutCr);
      if (headingMatch.matches()) {
        flushParagraph(events, paragraph);
        events.add(
            new HeadingSectionSplitter.Heading(
                headingMatch.group(1).length(), headingMatch.group(2)));
        continue;
      }
      if (withoutCr.isBlank()) {
        flushParagraph(events, paragraph);
        continue;
      }
      appendLine(paragraph, withoutCr);
    }
    flushParagraph(events, paragraph);
    return events;
  }

  private static void appendLine(StringBuilder paragraph, String line) {
    if (paragraph.length() > 0) {
      paragraph.append('\n');
    }
    paragraph.append(line);
  }

  private static void flushParagraph(
      List<HeadingSectionSplitter.Event> events, StringBuilder paragraph) {
    if (paragraph.length() > 0) {
      events.add(new HeadingSectionSplitter.Paragraph(paragraph.toString()));
      paragraph.setLength(0);
    }
  }
}
