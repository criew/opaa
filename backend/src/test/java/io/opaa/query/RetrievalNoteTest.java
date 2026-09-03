package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Pins the exact set of note and list-label templates the retrieval stages can produce - the closed
 * ones in {@link RetrievalNote} and {@link RetrievalListLabel}, and the three generic ones in
 * {@link StageStatus} that a not-run stage falls back to. A new or changed constant in any of the
 * three makes this test fail until {@code frontend/src/utils/retrieval-note-templates.json} is
 * regenerated (see {@link #regenerateCommittedFrontendExport}) - the same JSON {@code
 * retrievalProtocolText.test.ts} reads to check its translation inventory for coverage (#1207),
 * replacing what used to be three hand-maintained copies of this set (#1160).
 */
class RetrievalNoteTest {

  private static final String TRANSLATION_REMINDER =
      "A retrieval note/list-label template changed. Update the German translation in "
          + "frontend/src/utils/retrievalProtocolText.ts, then regenerate "
          + "frontend/src/utils/retrieval-note-templates.json by running `./gradlew test --tests "
          + "io.opaa.query.RetrievalNoteTest -Dopaa.retrievalNoteTemplates.regenerate=true` from "
          + "backend/, run `pnpm run format` in frontend/ on the regenerated file (Jackson's "
          + "pretty-printer and Prettier disagree on JSON layout), and review the diff.";

  @Test
  void notesAndListLabelsMatchTheCommittedFrontendExport() {
    RetrievalNoteTemplateExport.Export actual = RetrievalNoteTemplateExport.current();
    RetrievalNoteTemplateExport.Export committed = RetrievalNoteTemplateExport.committed();

    assertThat(actual.notes()).as(TRANSLATION_REMINDER).isEqualTo(committed.notes());
    assertThat(actual.listLabels()).as(TRANSLATION_REMINDER).isEqualTo(committed.listLabels());
  }

  /**
   * The regeneration path {@link #TRANSLATION_REMINDER} points at - off by default, so a regular
   * test run never rewrites the committed file underneath a genuine drift.
   */
  @Test
  @EnabledIfSystemProperty(named = "opaa.retrievalNoteTemplates.regenerate", matches = "true")
  void regenerateCommittedFrontendExport() {
    RetrievalNoteTemplateExport.regenerate();
  }

  @Test
  void everyNoteFormatsWithoutThrowing() {
    for (RetrievalNote note : RetrievalNote.values()) {
      assertThatCode(() -> note.format(dummyArgsFor(note.template())))
          .as("RetrievalNote.%s's template does not match its own placeholders", note.name())
          .doesNotThrowAnyException();
    }
  }

  @Test
  void everyListLabelFormatsWithoutThrowing() {
    for (RetrievalListLabel label : RetrievalListLabel.values()) {
      assertThatCode(() -> label.format(dummyArgsFor(label.template())))
          .as("RetrievalListLabel.%s's template does not match its own placeholders", label.name())
          .doesNotThrowAnyException();
    }
  }

  /**
   * One dummy argument per {@code %d}/{@code %s} placeholder in {@code template}, in the order they
   * appear - enough to exercise {@link RetrievalNote#format} and {@link RetrievalListLabel#format}
   * against a template's own placeholder count and types without needing a real call site.
   */
  private static Object[] dummyArgsFor(String template) {
    Matcher matcher = Pattern.compile("%[ds]").matcher(template);
    List<Object> args = new ArrayList<>();
    while (matcher.find()) {
      args.add("%d".equals(matcher.group()) ? 1 : "x");
    }
    return args.toArray();
  }
}
