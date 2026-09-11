package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #1536: what an administrator may put into a mail template. Plain variable tags from the key's
 * declared set and comments are accepted; every other Mustache construct is rejected with a message
 * naming the field.
 */
class MailPlaceholderValidatorTest {

  @Test
  void readsOrdinaryVariableTagsAndIgnoresWhitespaceAroundTheName() {
    assertThat(
            MailPlaceholderValidator.referencedPlaceholders(
                "Hallo {{displayName}}, {{ actionUrl }}"))
        .containsExactly("actionUrl", "displayName");
  }

  @Test
  void doesNotCountCommentsAsVariableReferences() {
    assertThat(MailPlaceholderValidator.referencedPlaceholders("{{! ein Kommentar }} Text"))
        .isEmpty();
  }

  @Test
  void treatsNullAndEmptyContentAsNoReferences() {
    assertThat(MailPlaceholderValidator.referencedPlaceholders(null)).isEmpty();
    assertThat(MailPlaceholderValidator.referencedPlaceholders("")).isEmpty();
  }

  /**
   * Regression guard for #1559 review, HIGH 3: these forms used to be silently skipped as "template
   * structure". They are not: an unescaped tag puts raw HTML from a variable into the message, and
   * the remaining forms render to nothing this subsystem can supply - every later send would have
   * failed, discovered by the person waiting for their mail.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "Hallo {{{displayName}}}",
        "Hallo {{&displayName}}",
        "{{>partial}}",
        "{{=<% %>=}}",
        "{{#abschnitt}}x{{/abschnitt}}",
        "{{^leer}}y{{/leer}}"
      })
  void rejectsEveryTagFormThatIsNeitherAPlainVariableNorAComment(String content) {
    assertThatThrownBy(() -> MailPlaceholderValidator.requireSupportedTags("bodyHtml", content))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("bodyHtml")
        .hasMessageContaining("Nicht unterstützte Vorlagen-Syntax");
  }

  @Test
  void acceptsPlainVariablesAndCommentsAsSupportedTags() {
    assertThatCode(
            () ->
                MailPlaceholderValidator.requireSupportedTags(
                    "bodyPlain", "{{! Hinweis }} Hallo {{displayName}}, {{ actionUrl }}"))
        .doesNotThrowAnyException();
    assertThatCode(() -> MailPlaceholderValidator.requireSupportedTags("bodyHtml", null))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsContentThatOnlyUsesDeclaredPlaceholders() {
    assertThatCode(
            () ->
                MailPlaceholderValidator.requireDeclaredPlaceholders(
                    MailTemplateKey.PASSWORD_RESET,
                    "bodyPlain",
                    "Guten Tag {{displayName}}, {{actionUrl}} ({{productName}},"
                        + " {{expiresAtHuman}})"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAnUndeclaredPlaceholderNamingTheFieldAndTheAcceptedNames() {
    assertThatThrownBy(
            () ->
                MailPlaceholderValidator.requireDeclaredPlaceholders(
                    MailTemplateKey.PASSWORD_RESET, "bodyPlain", "Hallo {{vorname}}"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("bodyPlain")
        .hasMessageContaining("{{vorname}}")
        .hasMessageContaining("{{displayName}}");
  }

  @Test
  void acceptsNullContentBecauseAnOptionalHtmlBodyIsAllowedToBeAbsent() {
    assertThatCode(
            () ->
                MailPlaceholderValidator.requireDeclaredPlaceholders(
                    MailTemplateKey.PASSWORD_RESET, "bodyHtml", null))
        .doesNotThrowAnyException();
  }
}
