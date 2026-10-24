package io.opaa.selfdisclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The start refuses two exporters of one category and a category the manifest does not lead. */
class SelfDisclosureExportersTest {

  @Test
  void sortsByCategoryAndRefusesDuplicatesAndUnknownCategories() {
    SelfDisclosureExporter notifications = exporter("notifications");
    SelfDisclosureExporter state = exporter("self-disclosure");

    assertThat(new SelfDisclosureExporters(List.of(state, notifications)).categoryIds())
        .containsExactly("notifications", "self-disclosure");
    assertThat(new SelfDisclosureExporters(List.of(state, notifications)).missingCategoryTitles())
        .contains("Benutzerkonten")
        .doesNotContain("Benachrichtigungen", "Stand der Selbstauskunft");
    assertThatThrownBy(() -> new SelfDisclosureExporters(List.of(notifications, notifications)))
        .hasMessageContaining("Two self-disclosure exporters deliver category notifications");
    assertThatThrownBy(() -> new SelfDisclosureExporters(List.of(exporter("mail-outbox"))))
        .hasMessageContaining("does not lead as a category");
  }

  private static SelfDisclosureExporter exporter(String id) {
    SelfDisclosureCategory category =
        new SelfDisclosureCategory(
            id, id, List.of(new Section("s", "S", List.of(Field.of("f", "F", "a.b")))));
    return new SelfDisclosureExporter() {
      @Override
      public SelfDisclosureCategory category() {
        return category;
      }

      @Override
      public void export(SelfDisclosureSubject subject, SelfDisclosureSink sink) {}
    };
  }
}
