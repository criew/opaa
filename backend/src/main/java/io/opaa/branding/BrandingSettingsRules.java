package io.opaa.branding;

import io.opaa.api.types.ColorScheme;
import io.opaa.common.FieldValidationException.FieldError;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The rules of the four text fields and the colour scheme of the branding, shared by the
 * administration ({@link BrandingSettingsService}) and the installation, so both refuse the same
 * values with the same German sentences and store the same normalised form. Each caller decides how
 * a problem reaches its API; the rules themselves only find it.
 */
public final class BrandingSettingsRules {

  public static final String TOO_LONG = "TOO_LONG";
  public static final String INVALID = "INVALID";

  /**
   * Six-digit hex triplet with a leading '#'. Deliberately no three-digit short form and no named
   * colours: the frontend derives hover/press/focus states from this value by darkening it, and one
   * canonical input form keeps that derivation - and the contrast check - from having to parse
   * variants.
   */
  private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

  static final String COLOR_INVALID =
      "Die Primärfarbe muss ein sechsstelliger Hex-Wert mit führendem '#' sein, zum Beispiel"
          + " #1292EE";

  private BrandingSettingsRules() {}

  /**
   * The values as stored: trimmed, {@code null} for "the OPAA default applies" in every field, the
   * notice {@code null} also when it equals the standard text.
   */
  public record Values(
      String productName,
      String claim,
      String aiNotice,
      String primaryColor,
      ColorScheme defaultColorScheme) {}

  /** The normalised values and every problem found, in the order of the fields. */
  public record Checked(Values values, List<FieldError> errors) {

    public Checked {
      errors = List.copyOf(errors);
    }
  }

  /**
   * Normalises and checks the fields; each problem is named as {@code prefix} plus its field name
   * ({@code ""} for the administration, {@code "branding."} for the installation). A field with a
   * problem is {@code null} in the returned values.
   */
  public static Checked check(
      String prefix,
      String productName,
      String claim,
      String aiNotice,
      String primaryColor,
      ColorScheme defaultColorScheme) {
    List<FieldError> errors = new ArrayList<>();
    String name =
        text(
            errors,
            prefix + "productName",
            productName,
            BrandingDefaults.MAX_PRODUCT_NAME_LENGTH,
            "Der Produktname");
    String validClaim =
        text(errors, prefix + "claim", claim, BrandingDefaults.MAX_CLAIM_LENGTH, "Der Claim");
    String notice = notice(errors, prefix + "aiNotice", aiNotice);
    String color = color(errors, prefix + "primaryColor", primaryColor);
    return new Checked(new Values(name, validClaim, notice, color, defaultColorScheme), errors);
  }

  /**
   * One text field: a value without a single visible character - blanks, no-break and ideographic
   * spaces, zero-width and other format characters - counts as empty ("back to the default"), so no
   * field can be blanked by a value that merely looks empty. Characters that render empty are cut
   * from the ends, except combining marks and tag characters that belong to a visible one before
   * them (an emoji's variation selector). Control characters and line or paragraph separators are
   * rejected, bidirectional controls wherever they stand, even at an edge or alone.
   */
  private static String text(
      List<FieldError> errors, String field, String value, int maxLength, String fieldLabel) {
    if (value == null) {
      return null;
    }
    if (value.codePoints().anyMatch(BrandingSettingsRules::isBidiControl)) {
      errors.add(new FieldError(field, INVALID, invalidMessage(fieldLabel)));
      return null;
    }
    if (value.codePoints().noneMatch(BrandingSettingsRules::isVisible)) {
      return null;
    }
    String trimmed = withoutInvisibleEdges(value);
    if (trimmed.length() > maxLength) {
      errors.add(
          new FieldError(
              field, TOO_LONG, fieldLabel + " darf höchstens " + maxLength + " Zeichen lang sein"));
      return null;
    }
    if (trimmed.codePoints().anyMatch(BrandingSettingsRules::isControlOrLineBreak)) {
      errors.add(new FieldError(field, INVALID, invalidMessage(fieldLabel)));
      return null;
    }
    return trimmed;
  }

  private static String invalidMessage(String fieldLabel) {
    return fieldLabel + " darf keine Steuerzeichen und keinen Zeilenumbruch enthalten";
  }

  /**
   * {@link #text} with the notice's limit; a text equal to the standard is stored as "not set" so
   * it keeps following the standard.
   */
  private static String notice(List<FieldError> errors, String field, String value) {
    String trimmed =
        text(
            errors,
            field,
            value,
            BrandingDefaults.MAX_AI_NOTICE_LENGTH,
            "Der Hinweis zur KI-Nutzung");
    return BrandingDefaults.AI_NOTICE.equals(trimmed) ? null : trimmed;
  }

  /**
   * A stored text field as it may be shown: {@code null} ("the default applies") when {@link
   * #check} would refuse or blank it today - no visible character, a control character, a line
   * break or a bidirectional control - otherwise unchanged. A value stored before the rules were
   * tightened thus never reaches a window title, the sign-in page, a mail or an export.
   */
  public static String readable(String stored) {
    if (stored == null
        || stored.codePoints().noneMatch(BrandingSettingsRules::isVisible)
        || stored.codePoints().anyMatch(BrandingSettingsRules::isControlOrLineBreak)) {
      return null;
    }
    return stored;
  }

  private static String color(List<FieldError> errors, String field, String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (!HEX_COLOR.matcher(trimmed).matches()) {
      errors.add(new FieldError(field, INVALID, COLOR_INVALID));
      return null;
    }
    return trimmed;
  }

  private static boolean isVisible(int codePoint) {
    return !Character.isWhitespace(codePoint)
        && !Character.isSpaceChar(codePoint)
        && !Character.isISOControl(codePoint)
        && Character.getType(codePoint) != Character.FORMAT
        && !isStandaloneBlank(codePoint)
        && !isCombiningBlank(codePoint);
  }

  /** Characters that render empty on their own although they are neither space nor format. */
  private static boolean isStandaloneBlank(int codePoint) {
    return codePoint == 0x115F
        || codePoint == 0x1160
        || codePoint == 0x3164
        || codePoint == 0xFFA0
        || codePoint == 0x2800;
  }

  /** Combining marks that render nothing by themselves (variation selectors and the like). */
  private static boolean isCombiningBlank(int codePoint) {
    return codePoint == 0x034F
        || codePoint == 0x17B4
        || codePoint == 0x17B5
        || (codePoint >= 0x180B && codePoint <= 0x180F)
        || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
        || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
  }

  private static boolean isTagCharacter(int codePoint) {
    return codePoint >= 0xE0020 && codePoint <= 0xE007F;
  }

  /**
   * Bidirectional embeddings, overrides and isolates reorder the text around them: a product name
   * in the window title, the notice before an appended answer.
   */
  private static boolean isBidiControl(int codePoint) {
    return (codePoint >= 0x202A && codePoint <= 0x202E)
        || (codePoint >= 0x2066 && codePoint <= 0x2069);
  }

  private static boolean isControlOrLineBreak(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isISOControl(codePoint)
        || isBidiControl(codePoint)
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }

  /** What may be cut from the end: invisible on its own, not part of the visible char before it. */
  private static boolean isTrailingNoise(int codePoint) {
    return Character.isWhitespace(codePoint)
        || Character.isSpaceChar(codePoint)
        || Character.isISOControl(codePoint)
        || isStandaloneBlank(codePoint)
        || (Character.getType(codePoint) == Character.FORMAT && !isTagCharacter(codePoint));
  }

  private static String withoutInvisibleEdges(String value) {
    int[] points = value.codePoints().toArray();
    int from = 0;
    int to = points.length;
    while (from < to && !isVisible(points[from])) {
      from++;
    }
    while (to > from && isTrailingNoise(points[to - 1])) {
      to--;
    }
    return new String(points, from, to - from);
  }
}
