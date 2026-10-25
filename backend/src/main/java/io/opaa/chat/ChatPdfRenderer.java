package io.opaa.chat;

import io.opaa.api.types.ChatRole;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;

/**
 * Lays a {@link ChatTranscript} out as an A4 PDF with the standard fonts, so nothing is embedded.
 * The answer's Markdown is reduced to readable text (no syntax characters); a character outside the
 * fonts' WinAnsi range is printed as {@code ?} rather than failing the export.
 */
final class ChatPdfRenderer {

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm 'Uhr'", Locale.GERMANY);

  private static final PDRectangle PAGE_SIZE = PDRectangle.A4;
  private static final float MARGIN = 56f;
  private static final float LINE_FACTOR = 1.35f;
  private static final float TITLE_SIZE = 18f;
  private static final float LABEL_SIZE = 11f;
  private static final float BODY_SIZE = 10.5f;
  private static final float SMALL_SIZE = 9f;
  private static final float INDENT = 14f;
  private static final float MUTED_GRAY = 0.33f;

  private static final Pattern TABLE_SEPARATOR =
      Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");
  private static final Pattern THEMATIC_BREAK = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
  private static final Pattern LIST_ITEM =
      Pattern.compile("^\\s*([-*+]|\\d+[.)])\\s+(\\[[ xX]\\]\\s+)?(.*)$");
  private static final String[][] INLINE_RULES = {
    {"!\\[([^\\]]*)\\]\\([^)]*\\)", "$1"},
    {"\\[([^\\]]+)\\]\\(([^)]*)\\)", "$1 ($2)"},
    {"`([^`]+)`", "$1"},
    {"(\\*\\*|__)(.+?)\\1", "$2"},
    {"~~(.+?)~~", "$1"},
    {"(^|[^\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)", "$1$2"},
    {"(^|[^\\w_])_(?!\\s)(.+?)(?<!\\s)_(?!\\w)", "$1$2"},
  };

  private final ZoneId zone;
  private final PDFont regular = new PDType1Font(FontName.HELVETICA);
  private final PDFont bold = new PDType1Font(FontName.HELVETICA_BOLD);
  private final PDFont italic = new PDType1Font(FontName.HELVETICA_OBLIQUE);
  private final PDFont mono = new PDType1Font(FontName.COURIER);
  private final Map<Integer, Boolean> encodable = new HashMap<>();

  private PDDocument document;
  private PDPageContentStream stream;
  private float y;

  ChatPdfRenderer(ZoneId zone) {
    this.zone = zone;
  }

  /**
   * @param note the closing note under the last turn, e.g. the AI notice with product name
   */
  byte[] render(ChatTranscript transcript, String note, Instant exportedAt) {
    try (PDDocument pdf = new PDDocument()) {
      document = pdf;
      PDDocumentInformation info = pdf.getDocumentInformation();
      info.setTitle(printable(transcript.title(), regular));
      newPage();
      writeTranscript(transcript, note, exportedAt);
      stream.close();
      numberPages();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      pdf.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("Rendering the chat export failed", e);
    } finally {
      document = null;
      stream = null;
    }
  }

  private void writeTranscript(ChatTranscript transcript, String note, Instant exportedAt)
      throws IOException {
    paragraph(transcript.title(), bold, TITLE_SIZE, 0, false);
    String meta =
        "Chat begonnen am "
            + format(transcript.createdAt())
            + " · exportiert am "
            + format(exportedAt);
    space(2);
    paragraph(meta, regular, SMALL_SIZE, 0, true);
    if (transcript.entries().isEmpty()) {
      space(12);
      paragraph("Dieser Chat enthält noch keine Nachrichten.", italic, BODY_SIZE, 0, false);
    }
    for (ChatTranscript.Entry entry : transcript.entries()) {
      space(14);
      String label = entry.role() == ChatRole.USER ? "Frage" : "Antwort";
      paragraph(label + " · " + format(entry.createdAt()), bold, LABEL_SIZE, 0, false);
      space(3);
      if (entry.role() == ChatRole.USER) {
        for (String line : entry.text().split("\\R", -1)) {
          paragraph(line, regular, BODY_SIZE, 0, false);
        }
      } else {
        markdown(entry.text());
      }
      if (!entry.sources().isEmpty()) {
        space(6);
        paragraph("Quellen", bold, SMALL_SIZE, 0, true);
        for (String source : entry.sources()) {
          paragraph(source, regular, SMALL_SIZE, INDENT, true);
        }
      }
    }
    if (note != null && !note.isBlank()) {
      space(18);
      paragraph(note, italic, SMALL_SIZE, 0, true);
    }
  }

  /** The answer's Markdown as text: headings bold, bullets as "•", code in a fixed-width font. */
  private void markdown(String text) throws IOException {
    boolean inCode = false;
    for (String line : text.split("\\R", -1)) {
      if (line.strip().startsWith("```") || line.strip().startsWith("~~~")) {
        inCode = !inCode;
        continue;
      }
      if (inCode) {
        paragraph(line, mono, SMALL_SIZE, INDENT, false);
        continue;
      }
      if (TABLE_SEPARATOR.matcher(line).matches() && line.contains("|")) {
        continue;
      }
      if (THEMATIC_BREAK.matcher(line).matches()) {
        space(4);
        continue;
      }
      if (line.matches("^\\s{0,3}#{1,6}\\s+.*")) {
        space(3);
        paragraph(inline(line.replaceFirst("^\\s{0,3}#{1,6}\\s+", "")), bold, BODY_SIZE, 0, false);
        continue;
      }
      String trimmed = line.strip();
      if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 1) {
        String[] cells = trimmed.substring(1, trimmed.length() - 1).split("\\|");
        List<String> plain = new ArrayList<>();
        for (String cell : cells) {
          plain.add(inline(cell.strip()));
        }
        paragraph(String.join("  |  ", plain), regular, BODY_SIZE, 0, false);
        continue;
      }
      String body = line.replaceFirst("^\\s*>\\s?", "");
      int depth = (body.length() - body.stripLeading().length()) / 2;
      Matcher bullet = LIST_ITEM.matcher(body);
      if (bullet.matches()) {
        String mark = bullet.group(1).matches("\\d+[.)]") ? bullet.group(1) : "•";
        paragraph(
            mark + " " + inline(bullet.group(3)), regular, BODY_SIZE, INDENT * (depth + 1), false);
        continue;
      }
      paragraph(inline(body.strip()), regular, BODY_SIZE, 0, false);
    }
  }

  private static String inline(String text) {
    String result = text;
    for (String[] rule : INLINE_RULES) {
      result = result.replaceAll(rule[0], rule[1]);
    }
    return result;
  }

  private String format(Instant instant) {
    return instant == null ? "unbekannt" : TIMESTAMP.format(instant.atZone(zone));
  }

  /** Writes {@code text} word-wrapped at the page width; an empty text is an empty line. */
  private void paragraph(String text, PDFont font, float size, float indent, boolean muted)
      throws IOException {
    float width = PAGE_SIZE.getWidth() - 2 * MARGIN - indent;
    for (String line : wrap(printable(text, font), font, size, width)) {
      float leading = size * LINE_FACTOR;
      if (y - leading < MARGIN) {
        stream.close();
        newPage();
      }
      y -= leading;
      if (line.isEmpty()) {
        continue;
      }
      stream.beginText();
      stream.setFont(font, size);
      stream.setNonStrokingColor(muted ? MUTED_GRAY : 0f);
      stream.newLineAtOffset(MARGIN + indent, y);
      stream.showText(line);
      stream.endText();
    }
  }

  private void space(float points) {
    y -= points;
  }

  private List<String> wrap(String text, PDFont font, float size, float width) throws IOException {
    List<String> lines = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String word : text.split(" ", -1)) {
      String candidate = current.isEmpty() ? word : current + " " + word;
      if (width(candidate, font, size) <= width) {
        current.setLength(0);
        current.append(candidate);
        continue;
      }
      if (!current.isEmpty()) {
        lines.add(current.toString());
        current.setLength(0);
      }
      String rest = word;
      while (width(rest, font, size) > width && rest.length() > 1) {
        int cut = rest.length() - 1;
        while (cut > 1 && width(rest.substring(0, cut), font, size) > width) {
          cut--;
        }
        lines.add(rest.substring(0, cut));
        rest = rest.substring(cut);
      }
      current.append(rest);
    }
    lines.add(current.toString());
    return lines;
  }

  private static float width(String text, PDFont font, float size) throws IOException {
    return font.getStringWidth(text) / 1000f * size;
  }

  /** {@code text} with tabs as spaces and every character the font cannot show as {@code ?}. */
  private String printable(String text, PDFont font) {
    StringBuilder result = new StringBuilder(text.length());
    text.replace("\t", "    ")
        .codePoints()
        .forEach(
            codePoint -> {
              if (Character.isISOControl(codePoint)) {
                return;
              }
              boolean ok =
                  encodable.computeIfAbsent(codePoint, unused -> canEncode(font, codePoint));
              result.appendCodePoint(ok ? codePoint : '?');
            });
    return result.toString();
  }

  /** All four fonts share WinAnsi, so one answer per code point holds for each of them. */
  private static boolean canEncode(PDFont font, int codePoint) {
    try {
      font.encode(new String(Character.toChars(codePoint)));
      return true;
    } catch (IllegalArgumentException | IOException e) {
      return false;
    }
  }

  private void newPage() throws IOException {
    PDPage page = new PDPage(PAGE_SIZE);
    document.addPage(page);
    stream = new PDPageContentStream(document, page);
    y = PAGE_SIZE.getHeight() - MARGIN;
  }

  private void numberPages() throws IOException {
    int total = document.getNumberOfPages();
    int number = 0;
    for (PDPage page : document.getPages()) {
      number++;
      String label = "Seite " + number + " von " + total;
      try (PDPageContentStream footer =
          new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
        footer.beginText();
        footer.setFont(regular, SMALL_SIZE);
        footer.setNonStrokingColor(MUTED_GRAY);
        float x = PAGE_SIZE.getWidth() - MARGIN - width(label, regular, SMALL_SIZE);
        footer.newLineAtOffset(x, MARGIN / 2);
        footer.showText(label);
        footer.endText();
      }
    }
  }
}
