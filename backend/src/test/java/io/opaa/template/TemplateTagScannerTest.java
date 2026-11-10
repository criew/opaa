package io.opaa.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
  void anythingBeyondFieldsListsConditionsAndTheAllowedFiltersIsRefused() {
    for (String template :
        List.of(
            "{{ (('%c'|format(60)) ~ 'w:fldSimple w:instr=\"INCLUDEPICTURE x\"/' ~ "
                + "('%c'|format(62)))|safe }}",
            "{{ ('X/w:tYXw:fldSimple w:instr=QINCLUDEPICTURE xQ/YXw:tY'|e)"
                + ".translate({88: 60, 89: 62, 81: 34, 90: 92}) }}",
            "{{ (x|e).translate(t) }}",
            "{{ x|forceescape }}",
            "{{ x|tojson }}",
            "{{ x | safe }}",
            "{% set y %}{{ x }}{% endset %}",
            "{% set y = x %}",
            "{% macro m() %}X{% endmacro %}",
            "{% call m() %}{% endcall %}",
            "{% with y = x %}{{ y }}{% endwith %}",
            "{% include 'a' %}",
            "{% filter safe %}{{ x }}{% endfilter %}",
            "{%- autoescape false -%}{{ x }}{% endautoescape %}",
            "{{ [x]|map('safe')|join }}",
            "{{ x|join(attribute='a') }}",
            "{{ x|default(y) }}",
            "{{ x|urlize }}",
            "{{ d|xmlattr }}",
            "{{ lipsum(1, true) }}",
            "{% for i in range(anzahl) %}{{ i }}{% endfor %}",
            "{{ x.upper() }}",
            "{{ x[::-1] }}",
            "{{ x[k] }}",
            "{{ x['__class__'] }}",
            "{{ x.__class__ }}",
            "{{ {'a': x} }}",
            "{{ self }}",
            "{% if x is sameas y %}{% endif %}")) {
      assertThatThrownBy(() -> TemplateTagScanner.scan(template))
          .as(template)
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("nur als Text");
    }
    assertThat(TemplateTagScanner.scan("{{ safety }}{# |safe #}{% raw %}{{ x|safe }}{% endraw %}"))
        .extracting(TemplatePlaceholder::name)
        .containsExactly("safety");
    assertThat(
            TemplateTagScanner.scan(
                "{{ a|default('-')|trim|title }}{{ b|round(1) }}{{ c|join(', ') }}{{ c|length }}"
                    + "{{ d['x'] ~ '!' }}{{ (a or b) and not c }}{{ 'j' if d.x else 'n' }}"
                    + "{% if b is not none and b > 1 %}{% endif %}{%tc vm %}{% colspan 2 %}"))
        .extracting(TemplatePlaceholder::name)
        .containsExactly("a", "b", "c", "d");
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

  @Test
  @Timeout(5)
  void unclosedOpeningsAreReadInLinearTime() {
    String text = "{{".repeat(400_000);

    assertThat(TemplateTagScanner.scan(text)).isEmpty();
  }

  @Test
  void textBeyondTheLimitIsRefused() {
    assertThatThrownBy(
            () -> TemplateTagScanner.scan("x".repeat(TemplateTagScanner.MAX_TEXT_CHARS + 1)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void whitespaceControlAndRowModifiersReadLikePlainTags() {
    assertThat(
            TemplateTagScanner.scan(
                "{%- if eilig -%}EILT{%- endif %}"
                    + "{%tr for m in massnahmen %}{{ m.titel }}{%tr endfor %}"
                    + "{{ p | upper }}"))
        .containsExactly(
            new TemplatePlaceholder("eilig", PlaceholderKind.CONDITION, List.of()),
            new TemplatePlaceholder("massnahmen", PlaceholderKind.LIST, List.of("titel")),
            new TemplatePlaceholder("p", PlaceholderKind.FIELD, List.of()));
  }

  @Test
  void rawBlocksTupleLoopsAndLoopFiltersAreRead() {
    assertThat(
            TemplateTagScanner.scan(
                "{% raw %}{{ kein }}{% endraw %}"
                    + "{% for (k, v) in paare %}{{ k }}{% endfor %}"
                    + "{% for i in anzahl %}{{ i }}{% endfor %}"
                    + "{% for z in zeilen if z.aktiv %}{{ z.text }}{% endfor %}"))
        .containsExactly(
            new TemplatePlaceholder("paare", PlaceholderKind.LIST, List.of()),
            new TemplatePlaceholder("anzahl", PlaceholderKind.LIST, List.of()),
            new TemplatePlaceholder("zeilen", PlaceholderKind.LIST, List.of("aktiv", "text")));
  }

  @Test
  void tooManyFieldsInListsAreRefused() {
    StringBuilder text = new StringBuilder("{% for e in liste %}");
    for (int i = 0; i <= TemplateTagScanner.MAX_FIELDS; i++) {
      text.append("{{ e.f").append(i).append(" }}");
    }
    text.append("{% endfor %}");
    assertThatThrownBy(() -> TemplateTagScanner.scan(text.toString()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Felder");
  }
}
