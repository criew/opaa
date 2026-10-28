package io.opaa.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.opaa.api.types.ColorScheme;
import io.opaa.common.FieldValidationException.FieldError;
import org.junit.jupiter.api.Test;

/**
 * The rules the branding administration and the installation share: every problem named under the
 * caller's prefix with the administration's sentence, blank means the OPAA default, and the notice
 * equal to the standard is stored as "not set".
 */
class BrandingSettingsRulesTest {

  @Test
  void blankFieldsAndTheStandardNoticeFollowTheDefault() {
    BrandingSettingsRules.Checked checked =
        BrandingSettingsRules.check(
            "branding.", "  ", "", " " + BrandingDefaults.AI_NOTICE + "\u200B", " ", null);

    assertThat(checked.errors()).isEmpty();
    assertThat(checked.values())
        .isEqualTo(new BrandingSettingsRules.Values(null, null, null, null, null));
  }

  @Test
  void valuesAreTrimmedAndKept() {
    BrandingSettingsRules.Checked checked =
        BrandingSettingsRules.check(
            "",
            " Landesamt-Assistent ",
            " Fragen. ",
            " Eigener Hinweis. ",
            " #7A3E9D ",
            ColorScheme.DARK);

    assertThat(checked.errors()).isEmpty();
    assertThat(checked.values())
        .isEqualTo(
            new BrandingSettingsRules.Values(
                "Landesamt-Assistent", "Fragen.", "Eigener Hinweis.", "#7A3E9D", ColorScheme.DARK));
  }

  @Test
  void everyProblemIsNamedUnderThePrefixWithTheSentenceOfTheAdministration() {
    BrandingSettingsRules.Checked checked =
        BrandingSettingsRules.check(
            "branding.",
            "x".repeat(BrandingDefaults.MAX_PRODUCT_NAME_LENGTH + 1),
            "Zei\u0007le",
            "Erste Zeile\u2028zweite Zeile",
            "blau",
            null);

    assertThat(checked.errors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactly(
            tuple("branding.productName", "TOO_LONG"),
            tuple("branding.claim", "INVALID"),
            tuple("branding.aiNotice", "INVALID"),
            tuple("branding.primaryColor", "INVALID"));
    assertThat(checked.errors())
        .extracting(FieldError::message)
        .containsExactly(
            "Der Produktname darf höchstens 60 Zeichen lang sein",
            "Der Claim darf keine Steuerzeichen enthalten",
            "Der Hinweis zur KI-Nutzung darf keine Steuerzeichen und keinen Zeilenumbruch enthalten",
            "Die Primärfarbe muss ein sechsstelliger Hex-Wert mit führendem '#' sein, zum Beispiel"
                + " #1292EE");
  }

  @Test
  void aNoticeWithoutAVisibleCharacterCannotSwitchTheNoticeOff() {
    BrandingSettingsRules.Checked checked =
        BrandingSettingsRules.check("", null, null, "\u00A0\u200B\u3000", null, null);

    assertThat(checked.errors()).isEmpty();
    assertThat(checked.values().aiNotice()).isNull();
  }
}
