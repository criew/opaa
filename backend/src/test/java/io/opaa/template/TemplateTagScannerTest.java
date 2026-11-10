package io.opaa.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TemplateTagScannerTest {

  @Test
  void readsFieldsListsAndConditionsInOrderOfAppearance() {
    List<TemplatePlaceholder> placeholders =
        TemplateTagScanner.scan(
            """
            Bericht: {{ titel }}
            {%p if zusammenfassung %}{{ zusammenfassung }}{%p endif %}
            {%tr for quelle in quellen %}{{ quelle.titel }} – {{ quelle.fundstelle|upper }}{%tr endfor %}
            Datum: {{ datum }}{# Kommentar {{ nicht_gezaehlt }} #}
            """);

    assertThat(placeholders)
        .containsExactly(
            new TemplatePlaceholder("titel", PlaceholderKind.FIELD, List.of()),
            new TemplatePlaceholder("zusammenfassung", PlaceholderKind.FIELD, List.of()),
            new TemplatePlaceholder(
                "quellen", PlaceholderKind.LIST, List.of("titel", "fundstelle")),
            new TemplatePlaceholder("datum", PlaceholderKind.FIELD, List.of()));
  }

  @Test
  void aValueUsedOnlyInAConditionIsACondition() {
    assertThat(TemplateTagScanner.scan("{% if eilig %}EILT{% else %}normal{% endif %}"))
        .containsExactly(new TemplatePlaceholder("eilig", PlaceholderKind.CONDITION, List.of()));
  }

  @Test
  void partsOfAnObjectAndNestedLoopsAreFields() {
    assertThat(
            TemplateTagScanner.scan(
                "{{ absender.name }}, {{ absender.ort }}"
                    + "{% for k in kapitel %}{% for a in k.abschnitte %}{{ a.text }}"
                    + "{% endfor %}{{ loop.index }}{% endfor %}"))
        .containsExactly(
            new TemplatePlaceholder("absender", PlaceholderKind.FIELD, List.of("name", "ort")),
            new TemplatePlaceholder("kapitel", PlaceholderKind.LIST, List.of("abschnitte")));
  }

  @Test
  void literalsFiltersAndTestsNameNoPlaceholder() {
    assertThat(
            TemplateTagScanner.scan(
                "{{ name | default('unbekannt') }}{% if betrag is defined and betrag > 0 %}"
                    + "{{ 'Text mit Wert' }}{% endif %}"))
        .containsExactly(
            new TemplatePlaceholder("name", PlaceholderKind.FIELD, List.of()),
            new TemplatePlaceholder("betrag", PlaceholderKind.CONDITION, List.of()));
  }

  @Test
  void anUnclosedBlockIsRefused() {
    assertThatThrownBy(() -> TemplateTagScanner.scan("{% for a in liste %}{{ a }}"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("endfor");
  }

  @Test
  void aClosingTagOfTheWrongBlockIsRefused() {
    assertThatThrownBy(() -> TemplateTagScanner.scan("{% if a %}x{% endfor %}"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("{% endfor %}");
  }

  @Test
  void tooManyPlaceholdersAreRefused() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i <= TemplateTagScanner.MAX_PLACEHOLDERS; i++) {
      text.append("{{ feld").append(i).append(" }}");
    }
    assertThatThrownBy(() -> TemplateTagScanner.scan(text.toString()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Platzhalter");
  }
}
