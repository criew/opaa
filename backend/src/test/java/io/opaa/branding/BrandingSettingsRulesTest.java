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
            "Der Claim darf keine Steuerzeichen und keinen Zeilenumbruch enthalten",
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

  @Test
  void aProductNameOrClaimWithoutAVisibleCharacterFollowsTheDefault() {
    for (String invisible : new String[] {"\u00A0", "\u200B", "\u00A0\u200B\u3000", "\uFE0F"}) {
      BrandingSettingsRules.Checked checked =
          BrandingSettingsRules.check("", invisible, invisible, null, null, null);

      assertThat(checked.errors())
          .as("value %s", invisible.codePoints().boxed().toList())
          .isEmpty();
      assertThat(checked.values().productName()).isNull();
      assertThat(checked.values().claim()).isNull();
    }
  }

  @Test
  void invisibleCharactersAtTheEdgesOfAProductNameAreNotStored() {
    BrandingSettingsRules.Checked checked =
        BrandingSettingsRules.check(
            "", "\u200B\u00A0Landesamt\u00A0", "\u3000Fragen.\u200B", null, null, null);

    assertThat(checked.errors()).isEmpty();
    assertThat(checked.values().productName()).isEqualTo("Landesamt");
    assertThat(checked.values().claim()).isEqualTo("Fragen.");
  }

  @Test
  void aBidiControlInProductNameOrClaimIsAFieldErrorWhereverItStands() {
    for (String reordering :
        new String[] {
          "Amt \u202Eredaeh", "\u202EAmt", "Amt\u202E", "Amt \u2066x\u2069", "\u202E"
        }) {
      BrandingSettingsRules.Checked checked =
          BrandingSettingsRules.check("branding.", reordering, reordering, null, null, null);

      assertThat(checked.errors())
          .as("value %s", reordering.codePoints().boxed().toList())
          .extracting(FieldError::field, FieldError::code, FieldError::message)
          .containsExactly(
              tuple(
                  "branding.productName",
                  "INVALID",
                  "Der Produktname darf keine Steuerzeichen und keinen Zeilenumbruch enthalten"),
              tuple(
                  "branding.claim",
                  "INVALID",
                  "Der Claim darf keine Steuerzeichen und keinen Zeilenumbruch enthalten"));
    }
  }

  @Test
  void aBidiControlAtTheEdgeOfTheNoticeIsRejectedRatherThanCut() {
    BrandingSettingsRules.Checked checked =
        BrandingSettingsRules.check("", null, null, "\u202EEigener Hinweis", null, null);

    assertThat(checked.errors()).extracting(FieldError::field).containsExactly("aiNotice");
  }

  private static final String FLAG_SCOTLAND = "🏴󠁧󠁢󠁳󠁣󠁴󠁿";

  @Test
  void tagCharactersOutsideAFlagSequenceAreAFieldError() {
    for (String smuggling :
        new String[] {
          "OPAA󠁉󠁇󠁎", "󠁉󠁇", "OPAA 🏴󠁧󠁢󠁳", "OPAA 🏴󠁉󠁇󠁎󠁏󠁒󠁅" + "󠀠󠁁󠁌󠁌󠁿", "OPAA 󠁿"
        }) {
      BrandingSettingsRules.Checked checked =
          BrandingSettingsRules.check("", smuggling, smuggling, smuggling, null, null);

      assertThat(checked.errors())
          .as("value %s", smuggling.codePoints().boxed().toList())
          .extracting(FieldError::field, FieldError::code)
          .containsExactly(
              tuple("productName", "INVALID"),
              tuple("claim", "INVALID"),
              tuple("aiNotice", "INVALID"));
    }
  }

  @Test
  void aSubdivisionFlagKeepsItsTagSequence() {
    BrandingSettingsRules.Checked checked =
        BrandingSettingsRules.check(
            "", "Amt " + FLAG_SCOTLAND, FLAG_SCOTLAND + " Fragen", null, null, null);

    assertThat(checked.errors()).isEmpty();
    assertThat(checked.values().productName()).isEqualTo("Amt " + FLAG_SCOTLAND);
    assertThat(checked.values().claim()).isEqualTo(FLAG_SCOTLAND + " Fragen");
  }

  @Test
  void fillerSymbolsAndMarksWithoutABaseAreNotVisible() {
    for (String filler : new String[] {"𝅙", "𖿤", " 𝅙 "}) {
      BrandingSettingsRules.Checked checked =
          BrandingSettingsRules.check("", filler, filler, filler, null, null);

      assertThat(checked.errors()).as("value %s", filler.codePoints().boxed().toList()).isEmpty();
      assertThat(checked.values())
          .isEqualTo(new BrandingSettingsRules.Values(null, null, null, null, null));
    }
  }

  @Test
  void unassignedNoncharactersLoneSurrogatesAndALeadingMarkAreAFieldError() {
    for (String broken :
        new String[] {"OPAA͸", "͸", "OPAA￿", "OPAA\uD800", "\uDC00OPAA", "́OPAA", "́"}) {
      BrandingSettingsRules.Checked checked =
          BrandingSettingsRules.check("", broken, broken, broken, null, null);

      assertThat(checked.errors())
          .as("value %s", broken.codePoints().boxed().toList())
          .extracting(FieldError::field, FieldError::code, FieldError::message)
          .containsExactly(
              tuple(
                  "productName",
                  "INVALID",
                  "Der Produktname darf keine unsichtbaren oder nicht darstellbaren Zeichen"
                      + " enthalten"),
              tuple(
                  "claim",
                  "INVALID",
                  "Der Claim darf keine unsichtbaren oder nicht darstellbaren Zeichen enthalten"),
              tuple(
                  "aiNotice",
                  "INVALID",
                  "Der Hinweis zur KI-Nutzung darf keine unsichtbaren oder nicht darstellbaren"
                      + " Zeichen enthalten"));
    }
  }

  @Test
  void aBaseCarriesAtMostFiveCombiningMarks() {
    String five = "Amt e" + "́".repeat(5);
    String six = "Amt e" + "́".repeat(6);
    String burmese = "မြို့";
    String vietnamese = "Việt Nam";

    BrandingSettingsRules.Checked accepted =
        BrandingSettingsRules.check("", five, burmese, vietnamese, null, null);
    BrandingSettingsRules.Checked refused =
        BrandingSettingsRules.check("", six, "क" + "़ँ".repeat(3), null, null, null);

    assertThat(accepted.errors()).isEmpty();
    assertThat(accepted.values().productName()).isEqualTo(five);
    assertThat(accepted.values().claim()).isEqualTo(burmese);
    assertThat(refused.errors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactly(tuple("productName", "INVALID"), tuple("claim", "INVALID"));
  }

  @Test
  void controlCharactersAtTheEdgesAreCutLikeBlanks() {
    BrandingSettingsRules.Checked checked =
        BrandingSettingsRules.check("", "\nOPAA\n", "\tFragen.\r\n", null, null, null);

    assertThat(checked.errors()).isEmpty();
    assertThat(checked.values().productName()).isEqualTo("OPAA");
    assertThat(checked.values().claim()).isEqualTo("Fragen.");
  }
}
