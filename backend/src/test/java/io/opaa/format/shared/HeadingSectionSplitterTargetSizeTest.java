package io.opaa.format.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.format.shared.HeadingSectionSplitter.Event;
import io.opaa.format.shared.HeadingSectionSplitter.Heading;
import io.opaa.format.shared.HeadingSectionSplitter.Paragraph;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * The target size is a parameter of the cut: splitting, combining tiny sections and the body budget
 * next to a repeated context all follow the configured value, while the hard ceiling and the
 * tiny-section threshold stay absolute.
 */
class HeadingSectionSplitterTargetSizeTest {

  private static final int TARGET = 2_000;
  private static final String HEADING = "II. Aufbewahrungsfristen";

  private final HeadingSectionSplitter splitter = new HeadingSectionSplitter(TARGET);

  @Test
  void aLongSectionIsCutAtTheConfiguredTargetSize() {
    String body = sentences(9_000);

    List<Document> configured = splitter.chunk(section(body), Integer.MAX_VALUE);
    List<Document> byDefault =
        HeadingSectionSplitterFixture.DEFAULT.chunk(section(body), Integer.MAX_VALUE);

    assertThat(configured)
        .allSatisfy(chunk -> assertThat(bodyOf(chunk).length()).isLessThanOrEqualTo(TARGET));
    assertThat(configured).hasSizeGreaterThan(byDefault.size());
    assertThat(byDefault)
        .anySatisfy(chunk -> assertThat(bodyOf(chunk).length()).isGreaterThan(TARGET));
  }

  @Test
  void tinySectionsAreCombinedUpToTheConfiguredTargetSize() {
    List<Event> events = new ArrayList<>();
    events.add(new Heading(1, "Haushaltsplan"));
    events.add(new Heading(2, "Kapitel 51"));
    for (int title = 1; title <= 60; title++) {
      events.add(new Heading(3, "Titel 51" + String.format("%03d", title)));
      events.add(new Paragraph("Sachverständige und Gutachten " + title + ".000 EUR"));
    }

    List<Document> configured = splitter.chunk(events, Integer.MAX_VALUE);
    List<Document> byDefault =
        HeadingSectionSplitterFixture.DEFAULT.chunk(events, Integer.MAX_VALUE);

    assertThat(configured).hasSizeGreaterThan(1);
    assertThat(configured)
        .allSatisfy(chunk -> assertThat(chunk.getText().length()).isLessThanOrEqualTo(TARGET));
    assertThat(byDefault).hasSizeLessThan(configured.size());
  }

  @Test
  void aUnitWithRepeatedContextKeepsHalfTheConfiguredTargetForItsBody() {
    String context = "Blatt: Gebühren · Tabelle: Gebühren\n\n" + "Spalte | ".repeat(170);
    assertThat(context.length()).isGreaterThan(TARGET / 2);

    List<Document> chunks = splitter.boundedChunks(context, "\n", lines(6_000), new HashMap<>());

    assertThat(splitter.bodyBudget(context.length() + 1)).isEqualTo(TARGET / 2);
    // the context a chunk repeats is cut first, which leaves the rest of the target to the body
    String repeated = RepeatedContext.cut(context);
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks)
        .allSatisfy(
            chunk -> {
              assertThat(chunk.getText()).startsWith(repeated + "\n");
              assertThat(chunk.getText().length() - repeated.length() - 1)
                  .isLessThanOrEqualTo(splitter.bodyBudget(repeated.length() + 1));
            });
  }

  @Test
  void theBodyBudgetFollowsTheConfiguredTarget() {
    assertThat(splitter.bodyBudget(500)).isEqualTo(TARGET - 500);
    assertThat(HeadingSectionSplitterFixture.DEFAULT.bodyBudget(500))
        .isEqualTo(HeadingSectionSplitter.DEFAULT_SOFT_CHUNK_CHAR_LIMIT - 500);
  }

  @Test
  void acceptsTargetsBetweenTheMinimumAndTheHardLimitOnly() {
    assertThatCode(
            () -> new HeadingSectionSplitter(HeadingSectionSplitter.MIN_SOFT_CHUNK_CHAR_LIMIT))
        .doesNotThrowAnyException();
    assertThatCode(() -> new HeadingSectionSplitter(HeadingSectionSplitter.HARD_CHUNK_CHAR_LIMIT))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> new HeadingSectionSplitter(HeadingSectionSplitter.MIN_SOFT_CHUNK_CHAR_LIMIT - 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new HeadingSectionSplitter(HeadingSectionSplitter.HARD_CHUNK_CHAR_LIMIT + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(splitter.softChunkCharLimit()).isEqualTo(TARGET);
  }

  private static List<Event> section(String body) {
    return List.of(new Heading(1, HEADING), new Paragraph(body));
  }

  private static String bodyOf(Document chunk) {
    return chunk.getText().substring((HEADING + "\n\n").length());
  }

  private static String sentences(int length) {
    StringBuilder text = new StringBuilder();
    for (int i = 1; text.length() < length; i++) {
      text.append("Die Frist Nummer ")
          .append(i)
          .append(" beginnt mit dem Ende des Kalenderjahres der letzten Eintragung. ");
    }
    return text.toString().strip();
  }

  private static String lines(int length) {
    StringBuilder text = new StringBuilder();
    for (int i = 1; text.length() < length; i++) {
      text.append("Gebühr ").append(i).append(" | 25,00 EUR | je angefangene Stunde\n");
    }
    return text.toString().strip();
  }
}
