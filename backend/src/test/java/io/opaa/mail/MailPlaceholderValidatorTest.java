package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import org.junit.jupiter.api.Test;

/**
 * #1536: what {@link MailPlaceholderValidator} counts as a variable reference, and that an
 * undeclared one is rejected with a message naming the field and the accepted names - the check
 * that turns a typo into a 400 for the administrator instead of a failed invitation for a user.
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
  void ignoresTemplateStructureThatIsNotAVariable() {
    String content =
        "{{! ein Kommentar }} {{> partial }} {{#abschnitt}}x{{/abschnitt}} {{^leer}}y{{/leer}}"
            + " {{= | | =}} {{&unescaped}} {{{tripleStache}}}";

    assertThat(MailPlaceholderValidator.referencedPlaceholders(content)).isEmpty();
  }

  @Test
  void treatsNullAndEmptyContentAsNoReferences() {
    assertThat(MailPlaceholderValidator.referencedPlaceholders(null)).isEmpty();
    assertThat(MailPlaceholderValidator.referencedPlaceholders("")).isEmpty();
  }

  @Test
  void acceptsContentThatOnlyUsesDeclaredPlaceholders() {
    assertThatCode(
            () ->
                MailPlaceholderValidator.requireDeclaredPlaceholders(
                    MailTemplateKey.PASSWORD_RESET,
                    "bodyPlain",
                    "Guten Tag {{displayName}}, {{actionUrl}} ({{productName}}, {{expiresAtHuman}})"))
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
