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
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;

/**
 * Lays a {@link ChatTranscript} out as an A4 PDF with the standard fonts, so nothing is embedded.
 * The answer's Markdown is reduced to readable text (no syntax characters). Typographic characters
 * outside the fonts' WinAnsi range get a plain stand-in ({@link #SUBSTITUTES}), invisible format
 * characters are dropped, anything else is printed as {@code ?} rather than failing the export.
 * Line breaking measures each glyph once, so its cost grows linearly with the text.
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

  /** Width available to a line without indent. */
  static final float TEXT_WIDTH = PAGE_SIZE.getWidth() - 2 * MARGIN;

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

  /** Plain stand-ins for characters the WinAnsi fonts lack but text commonly carries. */
  static final Map<Integer, String> SUBSTITUTES = substitutes();

  private static Map<Integer, String> substitutes() {
    Map<Integer, String> map = new HashMap<>();
    for (int space : new int[] {0x00A0, 0x2007, 0x202F, 0x205F, 0x3000}) {
      map.put(space, " ");
    }
    for (int space = 0x2000; space <= 0x200A; space++) {
      map.put(space, " ");
    }
    for (int hyphen : new int[] {0x2010, 0x2011, 0x2012, 0x2043, 0x2212}) {
      map.put(hyphen, "-");
    }
    map.put(0x2192, "->");
    map.put(0x2190, "<-");
    map.put(0x2194, "<->");
    map.put(0x21D2, "=>");
    map.put(0x21D0, "<=");
    map.put(0x2264, "<=");
    map.put(0x2265, ">=");
    map.put(0x2260, "!=");
    map.put(0x2248, "~");
    map.put(0x2032, "'");
    map.put(0x2033, "\"");
    return Map.copyOf(map);
  }

  private final ZoneId zone;
  private final PDFont regular = new PDType1Font(FontName.HELVETICA);
  private final PDFont bold = new PDType1Font(FontName.HELVETICA_BOLD);
  private final PDFont italic = new PDType1Font(FontName.HELVETICA_OBLIQUE);
  final PDFont mono = new PDType1Font(FontName.COURIER);
  private final Map<Integer, Boolean> encodable = new HashMap<>();
  private final Map<PDFont, Map<Integer, Float>> glyphWidths = new HashMap<>();

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
      pdf.getDocumentInformation().setTitle(transcript.title());
      pdf.getDocumentCatalog().setLanguage("de-DE");
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
      // The label never ends a page on its own: it moves on together with the first text line.
      keepTogether(LABEL_SIZE * LINE_FACTOR + 3 + BODY_SIZE * LINE_FACTOR);
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
        keepTogether(2 * SMALL_SIZE * LINE_FACTOR);
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
    for (String line : wrap(printable(text, font), font, size, TEXT_WIDTH - indent)) {
      float leading = size * LINE_FACTOR;
      if (y - leading < MARGIN) {
        stream.close();
        newPage();
      }
      y -= leading;
      if (line.isBlank()) {
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

  /** Starts a new page unless {@code height} still fits on the current one. */
  private void keepTogether(float height) throws IOException {
    if (y - height < MARGIN) {
      stream.close();
      newPage();
    }
  }

  /**
   * {@code text} broken into lines of at most {@code width}: at spaces where possible, inside an
   * over-long word between two code points otherwise. Leading spaces of the text stay (indented
   * code). Each code point is measured once from a cache, so the cost is linear in the text.
   */
  List<String> wrap(String text, PDFont font, float size, float width) {
    List<String> lines = new ArrayList<>();
    int start = 0;
    while (start < text.length() && text.charAt(start) == ' ') {
      start++;
    }
    StringBuilder current = new StringBuilder(text.substring(0, start));
    float currentWidth = start * glyphWidth(font, ' ', size);
    boolean hasWord = false;
    float space = glyphWidth(font, ' ', size);
    for (String word : text.substring(start).split(" ", -1)) {
      float wordWidth = 0;
      for (int i = 0; i < word.length(); i += Character.charCount(word.codePointAt(i))) {
        wordWidth += glyphWidth(font, word.codePointAt(i), size);
      }
      float separator = hasWord ? space : 0;
      if (currentWidth + separator + wordWidth <= width) {
        if (hasWord) {
          current.append(' ');
        }
        current.append(word);
        currentWidth += separator + wordWidth;
        hasWord = true;
        continue;
      }
      if (hasWord) {
        lines.add(current.toString());
        current.setLength(0);
        currentWidth = 0;
      }
      hasWord = true;
      for (int i = 0; i < word.length(); ) {
        int codePoint = word.codePointAt(i);
        float glyph = glyphWidth(font, codePoint, size);
        if (currentWidth + glyph > width && !current.isEmpty()) {
          lines.add(current.toString());
          current.setLength(0);
          currentWidth = 0;
        }
        current.appendCodePoint(codePoint);
        currentWidth += glyph;
        i += Character.charCount(codePoint);
      }
    }
    lines.add(current.toString());
    return lines;
  }

  private float glyphWidth(PDFont font, int codePoint, float size) {
    float units =
        glyphWidths
            .computeIfAbsent(font, unused -> new HashMap<>())
            .computeIfAbsent(codePoint, unused -> unitWidth(font, codePoint));
    return units / 1000f * size;
  }

  private static float unitWidth(PDFont font, int codePoint) {
    try {
      return font.getStringWidth(new String(Character.toChars(codePoint)));
    } catch (IllegalArgumentException | IOException e) {
      return unitWidth(font, '?');
    }
  }

  /**
   * {@code text} as the font can print it: tabs as spaces, control and format characters (zero
   * width, byte order mark, direction marks) dropped, {@link #SUBSTITUTES} applied, everything else
   * outside the font as {@code ?}.
   */
  String printable(String text, PDFont font) {
    StringBuilder result = new StringBuilder(text.length());
    text.replace("\t", "    ")
        .codePoints()
        .forEach(
            codePoint -> {
              if (Character.isISOControl(codePoint)
                  || Character.getType(codePoint) == Character.FORMAT) {
                return;
              }
              String substitute = SUBSTITUTES.get(codePoint);
              if (substitute != null) {
                result.append(substitute);
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
        float x =
            PAGE_SIZE.getWidth() - MARGIN - regular.getStringWidth(label) / 1000f * SMALL_SIZE;
        footer.newLineAtOffset(x, MARGIN / 2);
        footer.showText(label);
        footer.endText();
      }
    }
  }
}
