package io.opaa.indexing.metadata;

import io.opaa.common.ValidationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiles and applies the regular expression of a PATTERN field (#1071) under a hard step budget.
 *
 * <p>The management right at a library is <b>not</b> a trust boundary: every authenticated person
 * may create a library and is its OWNER, so a field pattern is user input like any other. A pattern
 * such as {@code (a+)+b} against a few dozen characters is catastrophic backtracking - the matching
 * thread would not return, and a handful of parallel calls would drain the servlet pool. Since
 * {@link Pattern} offers no timeout, the input is handed to the matcher as a {@link CharSequence}
 * whose {@code charAt} counts the matcher's steps and aborts once the budget is spent. That bounds
 * every pattern, including ones a syntactic check would miss.
 *
 * <p>A pattern is additionally exercised against a worst case at definition time, so a pathological
 * pattern is rejected where it is written rather than where a value is set.
 */
final class BoundedRegex {

  /**
   * How many characters the matcher may read. A linear match over the longest storable value costs
   * a small multiple of its length; anything beyond this is backtracking, not matching.
   */
  private static final int MAX_STEPS = 200_000;

  /** The worst case a pattern is tried against when it is defined - the longest storable value. */
  private static final int PROBE_LENGTH = 200;

  /** How many of the pattern's own literal characters are probed; bounds the check itself. */
  private static final int MAX_PROBE_ALPHABET = 6;

  private BoundedRegex() {}

  /** Signals that the budget was spent - never propagated to a caller as anything but a 400. */
  private static final class BudgetExceededException extends RuntimeException {
    BudgetExceededException() {
      super(null, null, false, false);
    }
  }

  /**
   * The compiled pattern of {@code field}. A stored pattern that no longer compiles is a validation
   * error rather than a 500: the field is unusable until corrected, and no value gets through
   * unchecked.
   */
  static Pattern compile(LibraryMetadataField field) {
    try {
      return Pattern.compile(field.getValuePattern());
    } catch (PatternSyntaxException e) {
      throw new ValidationException(
          "Das Muster des Feldes " + field.getLabel() + " ist ungültig: " + e.getDescription());
    }
  }

  /**
   * Whether {@code value} matches {@code pattern} entirely, within the step budget. Exceeding the
   * budget is a {@link ValidationException} - the value is refused rather than silently accepted or
   * silently rejected, and the person is told which side the fault is on.
   */
  static boolean matchesWithinBudget(Pattern pattern, String value, String fieldLabel) {
    try {
      return pattern.matcher(new BudgetedCharSequence(value, MAX_STEPS)).matches();
    } catch (BudgetExceededException e) {
      throw new ValidationException(
          "Das Muster des Feldes "
              + fieldLabel
              + " ist zu aufwendig auszuwerten und wurde abgebrochen");
    }
  }

  /**
   * Rejects a pattern that cannot be evaluated within the budget on its own worst case: the longest
   * storable value made of the characters the pattern itself mentions, plus a plain run of {@code
   * a}. Checked when the field is defined, so the cost never lands on whoever sets a value.
   */
  static void requireEvaluableWithinBudget(String pattern, Pattern compiled) {
    for (String probe : probes(pattern)) {
      try {
        Matcher matcher = compiled.matcher(new BudgetedCharSequence(probe, MAX_STEPS));
        matcher.matches();
      } catch (BudgetExceededException e) {
        throw new ValidationException(
            "Das Muster ist zu aufwendig auszuwerten (katastrophales Backtracking) und wird"
                + " abgelehnt");
      }
    }
  }

  /**
   * The worst cases a nested quantifier explodes on: a long run of one of the pattern's own literal
   * characters - the classic {@code (a+)+} against {@code aaaa…} without the terminator - plus a
   * run of a character the pattern never mentions, which forces the failing branch. At most {@link
   * #MAX_PROBE_ALPHABET} distinct characters, so the check itself stays bounded.
   */
  private static java.util.List<String> probes(String pattern) {
    java.util.LinkedHashSet<Character> literals = new java.util.LinkedHashSet<>();
    for (char c : pattern.toCharArray()) {
      if (Character.isLetterOrDigit(c) && literals.size() < MAX_PROBE_ALPHABET) {
        literals.add(c);
      }
    }
    java.util.List<String> probes = new java.util.ArrayList<>();
    for (char c : literals) {
      probes.add(String.valueOf(c).repeat(PROBE_LENGTH));
    }
    probes.add("a".repeat(PROBE_LENGTH));
    probes.add("!".repeat(PROBE_LENGTH));
    return probes;
  }

  /** A {@link CharSequence} that lets the matcher read at most {@code budget} characters. */
  private static final class BudgetedCharSequence implements CharSequence {

    private final CharSequence delegate;
    private final int[] remaining;

    BudgetedCharSequence(CharSequence delegate, int budget) {
      this(delegate, new int[] {budget});
    }

    private BudgetedCharSequence(CharSequence delegate, int[] remaining) {
      this.delegate = delegate;
      this.remaining = remaining;
    }

    @Override
    public int length() {
      return delegate.length();
    }

    @Override
    public char charAt(int index) {
      if (--remaining[0] < 0) {
        throw new BudgetExceededException();
      }
      return delegate.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return new BudgetedCharSequence(delegate.subSequence(start, end), remaining);
    }

    @Override
    public String toString() {
      return delegate.toString();
    }
  }
}
