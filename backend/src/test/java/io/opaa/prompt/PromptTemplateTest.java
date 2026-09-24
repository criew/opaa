package io.opaa.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.PromptVariableType;
import io.opaa.common.ValidationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The variable rules of a prompt, in both directions between text and definitions. */
class PromptTemplateTest {

  @Test
  void aTextAndItsDefinitionsThatAgreeAreAccepted() {
    assertThatCode(
            () ->
                PromptTemplate.validate(
                    "Anhörung zu {{aktenzeichen}} vom {{frist}}, Art: {{art}}. {{aktenzeichen}}",
                    List.of(
                        text("aktenzeichen"),
                        variable("frist", PromptVariableType.DATE, "2026-10-01", List.of()),
                        variable(
                            "art",
                            PromptVariableType.SELECT,
                            "schriftlich",
                            List.of("schriftlich", "mündlich")))))
        .doesNotThrowAnyException();
  }

  @Test
  void aPlaceholderWithoutDefinitionIsRefused() {
    assertThatThrownBy(() -> PromptTemplate.validate("Hallo {{name}}", List.of()))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Die Variable „{{name}}“ wird im Text verwendet, ist aber nicht definiert.");
  }

  @Test
  void aDefinitionTheTextDoesNotUseIsRefused() {
    assertThatThrownBy(() -> PromptTemplate.validate("Hallo", List.of(text("name"))))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Die Variable „name“ ist definiert, wird im Text aber nicht verwendet.");
  }

  @Test
  void theSystemVariablesNeedNoDefinitionAndCannotBeDefined() {
    assertThatCode(
            () -> PromptTemplate.validate("Stand {{CURRENT_DATE}}, {{USER_NAME}}", List.of()))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> PromptTemplate.validate("{{current_date}}", List.of(text("current_date"))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("ist eine Systemvariable");
    assertThatThrownBy(() -> PromptTemplate.validate("{{USER_NAME}}", List.of(text("USER_NAME"))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("ist eine Systemvariable");
  }

  @Test
  void aMalformedPlaceholderIsRefusedRatherThanKeptAsText() {
    for (String text : List.of("{{ name }}", "{{1name}}", "{{na-me}}", "{{}}", "{{{name}}}")) {
      assertThatThrownBy(() -> PromptTemplate.validate(text, List.of()))
          .as(text)
          .isInstanceOf(ValidationException.class)
          .hasMessageStartingWith("Ungültiger Platzhalter");
    }
    assertThatCode(() -> PromptTemplate.validate("Geschweifte { Klammer } und {{", List.of()))
        .as("a brace that opens no complete placeholder stays text")
        .doesNotThrowAnyException();
  }

  @Test
  void theDefinitionsThemselvesAreChecked() {
    assertThatThrownBy(() -> PromptTemplate.validate("{{a}} {{a}}", List.of(text("a"), text("a"))))
        .hasMessage("Die Variable „a“ ist mehrfach definiert.");
    assertThatThrownBy(
            () ->
                PromptTemplate.validate(
                    "{{a}}",
                    List.of(
                        new PromptVariable("a", " ", PromptVariableType.TEXT, true, null, null))))
        .hasMessageContaining("braucht eine Beschriftung");
    assertThatThrownBy(
            () ->
                PromptTemplate.validate(
                    "{{a}}", List.of(new PromptVariable("a", "A", null, true, null, null))))
        .hasMessageContaining("braucht einen Typ");
    assertThatThrownBy(
            () ->
                PromptTemplate.validate(
                    "{{a}}", List.of(variable("a", PromptVariableType.TEXT, null, List.of("x")))))
        .hasMessageContaining("nur für eine Auswahl zulässig");
    assertThatThrownBy(
            () ->
                PromptTemplate.validate(
                    "{{a}}", List.of(variable("a", PromptVariableType.SELECT, null, List.of()))))
        .hasMessageContaining("braucht zwischen 1 und 50 Auswahlwerte");
    assertThatThrownBy(
            () ->
                PromptTemplate.validate(
                    "{{a}}",
                    List.of(variable("a", PromptVariableType.SELECT, null, List.of("x", "x")))))
        .hasMessageContaining("steht mehrfach");
    assertThatThrownBy(
            () ->
                PromptTemplate.validate(
                    "{{a}}",
                    List.of(
                        variable("a", PromptVariableType.SELECT, null, Arrays.asList("x", null)))))
        .hasMessageContaining("ist leer");
    assertThatThrownBy(
            () ->
                PromptTemplate.validate(
                    "{{a}}", List.of(variable("a", PromptVariableType.SELECT, "y", List.of("x")))))
        .hasMessageContaining("keiner ihrer Auswahlwerte");
    assertThatThrownBy(
            () ->
                PromptTemplate.validate(
                    "{{a}}",
                    List.of(variable("a", PromptVariableType.DATE, "1.10.2026", List.of()))))
        .hasMessageContaining("kein Datum");
    assertThatThrownBy(() -> PromptTemplate.validate("{{a}}", Arrays.asList((PromptVariable) null)))
        .hasMessageContaining("ist leer");
  }

  @Test
  void aPromptHasAtMostTwentyVariables() {
    List<PromptVariable> variables = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (int index = 0; index < 21; index++) {
      variables.add(text("v" + index));
      text.append("{{v").append(index).append("}} ");
    }

    assertThatThrownBy(() -> PromptTemplate.validate(text.toString(), variables))
        .hasMessage("Ein Prompt kann höchstens 20 Variablen haben.");
    assertThatCode(
            () ->
                PromptTemplate.validate(
                    text.toString().replace("{{v20}} ", ""), variables.subList(0, 20)))
        .doesNotThrowAnyException();
  }

  @Test
  void placeholdersAreListedInOrderOfFirstUse() {
    assertThat(PromptTemplate.placeholders("{{b}} {{a}} {{b}} {{CURRENT_DATE}}"))
        .containsExactly("b", "a", "CURRENT_DATE");
  }

  private static PromptVariable text(String name) {
    return variable(name, PromptVariableType.TEXT, null, List.of());
  }

  private static PromptVariable variable(
      String name, PromptVariableType type, String defaultValue, List<String> options) {
    return new PromptVariable(name, "Beschriftung " + name, type, true, defaultValue, options);
  }
}
