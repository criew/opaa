package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import io.opaa.api.types.ChatRole;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ChatPdfRendererTest {

  private static final Instant AT = Instant.parse("2026-03-05T09:15:00Z");
  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  private final ChatPdfRenderer renderer = new ChatPdfRenderer(BERLIN);
  private final PDFont helvetica = new PDType1Font(FontName.HELVETICA);

  private static ChatTranscript answer(String text) {
    return new ChatTranscript(
        "Test", AT, List.of(new ChatTranscript.Entry(ChatRole.ASSISTANT, AT, text, List.of())));
  }

  private String text(ChatTranscript transcript) throws IOException {
    byte[] pdf = renderer.render(transcript, "Vermerk", AT);
    try (PDDocument document = Loader.loadPDF(pdf)) {
      return new PDFTextStripper().getText(document);
    }
  }

  private float width(String line, PDFont font, float size) throws IOException {
    return font.getStringWidth(line) / 1000f * size;
  }

  @Test
  void aVeryLongWordWithoutSpacesIsBrokenInLinearTime() {
    String word = "x".repeat(50_000);

    byte[] pdf =
        assertTimeout(Duration.ofSeconds(2), () -> renderer.render(answer(word), "Vermerk", AT));

    assertThat(pdf).isNotEmpty();
  }

  @Test
  void anOverLongWordIsCutIntoLinesThatFitTheWidth() throws IOException {
    List<String> lines = renderer.wrap("a".repeat(500) + " Ende", helvetica, 10, 200);

    assertThat(lines).hasSizeGreaterThan(2);
    assertThat(String.join("", lines).replace(" ", "")).isEqualTo("a".repeat(500) + "Ende");
    for (String line : lines) {
      assertThat(width(line, helvetica, 10)).isLessThanOrEqualTo(200);
    }
  }

  @Test
  void aCutNeverSplitsASurrogatePair() {
    String emoji = "😀";

    List<String> lines = renderer.wrap(emoji.repeat(300), helvetica, 10, 100);

    assertThat(lines).hasSizeGreaterThan(1);
    for (String line : lines) {
      assertThat(Character.isLowSurrogate(line.charAt(0))).isFalse();
      assertThat(Character.isHighSurrogate(line.charAt(line.length() - 1))).isFalse();
    }
  }

  @Test
  void codeKeepsItsLeadingIndentation() {
    assertThat(renderer.wrap("    return frist;", renderer.mono, 9, 400))
        .containsExactly("    return frist;");
  }

  @Test
  void formatCharactersAreDroppedAndCommonSymbolsGetAPlainStandIn() {
    String printed = renderer.printable("a​b﻿c‮d 10 % x ‑ − → ← ≤ ≥ 契", helvetica);

    assertThat(printed).isEqualTo("abcd 10 % x - - -> <- <= >= ?");
  }

  @Test
  void tablesNestedListsAndCodeBlocksBecomeReadableText() throws IOException {
    String markdown =
        String.join(
            "\n",
            "| Frist | Beginn |",
            "|---|---|",
            "| Ein Monat | Zugang |",
            "",
            "- außen",
            "  - innen",
            "",
            "```",
            "frist = 30",
            "```");

    String text = text(answer(markdown));

    assertThat(text)
        .containsPattern("Frist\\s+\\|\\s+Beginn")
        .containsPattern("Ein Monat\\s+\\|\\s+Zugang")
        .doesNotContain("---")
        .contains("• außen")
        .contains("• innen")
        .contains("frist = 30")
        .doesNotContain("```");
  }

  @Test
  void anAnswerLongerThanAPageContinuesOnTheNext() throws IOException {
    StringBuilder markdown = new StringBuilder();
    for (int i = 1; i <= 120; i++) {
      markdown.append("Absatz ").append(i).append(" der langen Antwort.\n");
    }

    String text = text(answer(markdown.toString()));

    assertThat(text).contains("Absatz 1 der").contains("Absatz 120 der").contains("Seite 2 von");
  }
}
