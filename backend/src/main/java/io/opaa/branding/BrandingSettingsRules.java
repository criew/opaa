package io.opaa.branding;

import io.opaa.api.types.ColorScheme;
import io.opaa.common.FieldValidationException.FieldError;
import java.util.ArrayList;
import java.util.Arrays;
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

  /**
   * Combining marks one base may carry. Real orthographies stay below it (Vietnamese two, Burmese
   * and Tibetan stacks four or five); beyond it marks pile up over the line ("Zalgo text") and
   * spill into the window title or the sign-in page.
   */
  static final int MAX_MARKS_PER_BASE = 5;

  private static final int TAG_FIRST = 0xE0000;
  private static final int TAG_CANCEL = 0xE007F;
  private static final int WAVING_BLACK_FLAG = 0x1F3F4;

  /** Length of the subdivision code in a flag tag sequence, "gbsct" or "usca" (UTS #51). */
  private static final int MIN_FLAG_TAGS = 3;

  private static final int MAX_FLAG_TAGS = 7;

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
   * One text field. Rejected wherever they stand: bidirectional controls, unassigned code points,
   * noncharacters, lone surrogates and tag characters outside a subdivision flag (hidden text).
   * Blanks, control and format characters are cut from both ends, combining blanks from the start;
   * what remains without a visible character counts as empty ("back to the default"). A combining
   * mark at the start, more than {@value #MAX_MARKS_PER_BASE} on one base, a control character or a
   * line break inside are rejected.
   */
  private static String text(
      List<FieldError> errors, String field, String value, int maxLength, String fieldLabel) {
    if (value == null) {
      return null;
    }
    if (value.codePoints().anyMatch(BrandingSettingsRules::isBidiControl)) {
      errors.add(new FieldError(field, INVALID, controlMessage(fieldLabel)));
      return null;
    }
    int[] points = value.codePoints().toArray();
    if (Arrays.stream(points).anyMatch(BrandingSettingsRules::isUndisplayable)
        || hasTagOutsideFlag(points)) {
      errors.add(new FieldError(field, INVALID, undisplayableMessage(fieldLabel)));
      return null;
    }
    int[] trimmed = withoutInvisibleEdges(points);
    if (trimmed.length > 0 && isCombiningMark(trimmed[0]) && !isCombiningBlank(trimmed[0])) {
      errors.add(new FieldError(field, INVALID, undisplayableMessage(fieldLabel)));
      return null;
    }
    if (Arrays.stream(trimmed).noneMatch(BrandingSettingsRules::isVisible)) {
      return null;
    }
    String text = new String(trimmed, 0, trimmed.length);
    if (text.length() > maxLength) {
      errors.add(
          new FieldError(
              field, TOO_LONG, fieldLabel + " darf höchstens " + maxLength + " Zeichen lang sein"));
      return null;
    }
    if (Arrays.stream(trimmed).anyMatch(BrandingSettingsRules::isControlOrLineBreak)) {
      errors.add(new FieldError(field, INVALID, controlMessage(fieldLabel)));
      return null;
    }
    if (hasTooManyMarksOnOneBase(trimmed)) {
      errors.add(new FieldError(field, INVALID, undisplayableMessage(fieldLabel)));
      return null;
    }
    return text;
  }

  private static String controlMessage(String fieldLabel) {
    return fieldLabel + " darf keine Steuerzeichen und keinen Zeilenumbruch enthalten";
  }

  private static String undisplayableMessage(String fieldLabel) {
    return fieldLabel + " darf keine unsichtbaren oder nicht darstellbaren Zeichen enthalten";
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
   * A stored text field as it may be shown: {@code null} ("the default applies") when the character
   * rules of {@link #check} would refuse or blank it, otherwise with its invisible edges cut. The
   * length is not checked. A stored value that breaks these rules thus never reaches a window
   * title, the sign-in page, a mail or an export.
   */
  public static String readable(String stored) {
    List<FieldError> problems = new ArrayList<>();
    String text = text(problems, "", stored, Integer.MAX_VALUE, "");
    return problems.isEmpty() ? text : null;
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

  /**
   * A letter, digit, punctuation mark or symbol that is not one of the fillers rendering empty.
   * Space, marks, controls and format characters are never visible on their own.
   */
  private static boolean isVisible(int codePoint) {
    return switch (Character.getType(codePoint)) {
      case Character.UPPERCASE_LETTER,
          Character.LOWERCASE_LETTER,
          Character.TITLECASE_LETTER,
          Character.MODIFIER_LETTER,
          Character.OTHER_LETTER,
          Character.DECIMAL_DIGIT_NUMBER,
          Character.LETTER_NUMBER,
          Character.OTHER_NUMBER,
          Character.CONNECTOR_PUNCTUATION,
          Character.DASH_PUNCTUATION,
          Character.START_PUNCTUATION,
          Character.END_PUNCTUATION,
          Character.INITIAL_QUOTE_PUNCTUATION,
          Character.FINAL_QUOTE_PUNCTUATION,
          Character.OTHER_PUNCTUATION,
          Character.MATH_SYMBOL,
          Character.CURRENCY_SYMBOL,
          Character.MODIFIER_SYMBOL,
          Character.OTHER_SYMBOL ->
          !isFiller(codePoint);
      default -> false;
    };
  }

  /** Letters and symbols that render empty although their category says otherwise. */
  private static boolean isFiller(int codePoint) {
    return codePoint == 0x115F
        || codePoint == 0x1160
        || codePoint == 0x3164
        || codePoint == 0xFFA0
        || codePoint == 0x2800
        || codePoint == 0x1D159;
  }

  private static boolean isCombiningMark(int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK
        || type == Character.ENCLOSING_MARK;
  }

  /** Combining marks that render nothing by themselves (variation selectors and the like). */
  private static boolean isCombiningBlank(int codePoint) {
    return codePoint == 0x034F
        || codePoint == 0x17B4
        || codePoint == 0x17B5
        || codePoint == 0x16FE4
        || (codePoint >= 0x180B && codePoint <= 0x180F)
        || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
        || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
  }

  /** Unassigned code points, noncharacters and surrogates without their partner. */
  private static boolean isUndisplayable(int codePoint) {
    int type = Character.getType(codePoint);
    return type == Character.UNASSIGNED || type == Character.SURROGATE;
  }

  private static boolean isTagCharacter(int codePoint) {
    return codePoint >= TAG_FIRST && codePoint <= TAG_CANCEL;
  }

  /** Tag letters a-z and digits 0-9, the alphabet of a subdivision code. */
  private static boolean isFlagTag(int codePoint) {
    return (codePoint >= 0xE0061 && codePoint <= 0xE007A)
        || (codePoint >= 0xE0030 && codePoint <= 0xE0039);
  }

  /**
   * Tag characters are invisible ASCII and could carry hidden text into every copy of the branding;
   * they pass only as a subdivision flag: the black flag, a short code, the cancel tag.
   */
  private static boolean hasTagOutsideFlag(int[] points) {
    int i = 0;
    while (i < points.length) {
      if (points[i] == WAVING_BLACK_FLAG) {
        int end = i + 1;
        while (end < points.length && isFlagTag(points[end])) {
          end++;
        }
        int tags = end - i - 1;
        if (tags > 0) {
          if (tags < MIN_FLAG_TAGS
              || tags > MAX_FLAG_TAGS
              || end >= points.length
              || points[end] != TAG_CANCEL) {
            return true;
          }
          i = end + 1;
          continue;
        }
      } else if (isTagCharacter(points[i])) {
        return true;
      }
      i++;
    }
    return false;
  }

  private static boolean hasTooManyMarksOnOneBase(int[] points) {
    int marks = 0;
    for (int point : points) {
      if (isCombiningMark(point)) {
        marks++;
        if (marks > MAX_MARKS_PER_BASE) {
          return true;
        }
      } else if (Character.getType(point) != Character.FORMAT) {
        marks = 0;
      }
    }
    return false;
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

  /** What may be cut from either end: blank, control or format, never part of a flag. */
  private static boolean isEdgeNoise(int codePoint) {
    return Character.isWhitespace(codePoint)
        || Character.isSpaceChar(codePoint)
        || Character.isISOControl(codePoint)
        || isFiller(codePoint)
        || (Character.getType(codePoint) == Character.FORMAT && !isTagCharacter(codePoint));
  }

  /**
   * Cuts edge noise from both ends and combining blanks from the start; at the end they belong to
   * the visible character before them (an emoji's variation selector).
   */
  private static int[] withoutInvisibleEdges(int[] points) {
    int from = 0;
    int to = points.length;
    while (from < to && (isEdgeNoise(points[from]) || isCombiningBlank(points[from]))) {
      from++;
    }
    while (to > from && isEdgeNoise(points[to - 1])) {
      to--;
    }
    return Arrays.copyOfRange(points, from, to);
  }
}
