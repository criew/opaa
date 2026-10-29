package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ReDoS self-test of every static {@link Pattern} in {@code io.opaa.format}: each runs over pumped
 * inputs of {@link #RUN} characters within {@link #BOUND}, searched and matched whole. A linear
 * pattern needs milliseconds, a quadratic one seconds. A pattern {@link #isRisky} - a lazy
 * quantifier, a quantified group holding a quantifier, or two unbounded greedy quantifiers - also
 * runs the inputs {@link #ATTACKS} names for it, and fails without them.
 */
class FormatPatternBacktrackingTest {

  static final int RUN = 50_000;

  static final Duration BOUND = Duration.ofSeconds(2);

  /** Inputs that pump the quantifiers of each risky pattern, keyed {@code <class>#<field>}. */
  static final Map<String, List<String>> ATTACKS =
      Map.of(
          "io.opaa.format.file.markdown.MarkdownDocumentFormat#FRONTMATTER_ENTRY",
          List.of(
              "k: v" + " ".repeat(RUN) + "x",
              "k: v" + " \t".repeat(RUN / 2) + "\u0000",
              "k" + " ".repeat(RUN) + "v",
              "k".repeat(RUN) + " "),
          "io.opaa.format.file.mail.MailThreadSplitter#SEPARATOR",
          List.of(
              "-----" + " ".repeat(RUN) + "-----",
              "-".repeat(RUN) + " Original Message " + "-".repeat(RUN) + "x",
              ("Am " + "x schrieb ".repeat(40) + "\n").repeat(RUN / 400)),
          "io.opaa.format.shared.IngestTextSanitizer#INSTRUCTION_PHRASE",
          List.of(
              "ignore" + " ".repeat(RUN) + "all" + " ".repeat(RUN) + "x",
              ("ignoriere " + "alle ".repeat(10)).repeat(RUN / 60)),
          "io.opaa.format.chunk.MarkdownHeading#LINE",
          List.of("# " + " \t".repeat(RUN / 2), "#".repeat(RUN) + " "),
          "io.opaa.format.chunk.MarkdownHeading#IN_TEXT",
          List.of("# " + " \t".repeat(RUN / 2), ("\f# " + " ".repeat(40)).repeat(RUN / 43)),
          "io.opaa.format.chunk.ChunkLocationResolver#HEADING",
          List.of("# " + " \t".repeat(RUN / 2), ("\f# " + " ".repeat(40)).repeat(RUN / 43)));

  /** Runs of one character, or two alternating, that every pattern meets. */
  private static final List<String> PUMPS =
      List.of(" ", "\t", "\n", "\f", "a", "A", "0", "-", "#", ".", "<", "|", "=", ":", "/", " a");

  @ParameterizedTest(name = "{0}")
  @MethodSource("patterns")
  void everyPatternRunsInBoundedTime(String name, Pattern pattern) {
    List<String> inputs = new ArrayList<>(ATTACKS.getOrDefault(name, List.of()));
    for (String pump : PUMPS) {
      inputs.add(pump.repeat(RUN / pump.length()) + "\u0000");
    }

    assertTimeoutPreemptively(
        BOUND,
        () -> {
          for (String input : inputs) {
            Matcher matcher = pattern.matcher(input);
            while (matcher.find()) {
              // consume every match, as a splitter or sanitizer does
            }
            matcher.reset().matches();
          }
        },
        () -> name + " backtracks past " + BOUND + " on a pumped input of " + RUN + " characters");
  }

  @Test
  void everyRiskyPatternNamesItsAttacks() {
    Map<String, Pattern> patterns = formatPatterns();
    Set<String> risky = new TreeSet<>();
    patterns.forEach(
        (name, pattern) -> {
          if (isRisky(pattern.pattern())) {
            risky.add(name);
          }
        });

    assertThat(SecurityInvariants.stale(risky, ATTACKS.keySet()))
        .as("risky patterns without inputs in ATTACKS that pump their quantifiers")
        .isEmpty();
    assertThat(SecurityInvariants.stale(ATTACKS.keySet(), patterns.keySet()))
        .as("ATTACKS for patterns that are gone")
        .isEmpty();
  }

  @Test
  void theHeuristicTellsRiskyFromLinearPatterns() {
    assertThat(isRisky("^(\\w+)\\s*:\\s*(\\S.*?)\\s*$")).isTrue();
    assertThat(isRisky("(a+)+b")).isTrue();
    assertThat(isRisky("\\s+x\\s+")).isTrue();
    assertThat(isRisky("https?://\\S++")).isFalse();
    assertThat(isRisky("[\\s\\u00A0]+")).isFalse();
    assertThat(isRisky("(?m)^ {0,3}(#{1,6})[ \\t]+x")).isFalse();
    assertThat(isRisky("[*+]{2,}x")).isFalse();
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> patterns() {
    return formatPatterns().entrySet().stream()
        .map(e -> org.junit.jupiter.params.provider.Arguments.of(e.getKey(), e.getValue()));
  }

  /** Every static {@link Pattern} field of the classes in {@code io.opaa.format}. */
  static Map<String, Pattern> formatPatterns() {
    Map<String, Pattern> patterns = new TreeMap<>();
    for (JavaClass type : MainClasses.get()) {
      if (!type.getPackageName().startsWith("io.opaa.format")) {
        continue;
      }
      for (JavaField field : type.getFields()) {
        if (field.getModifiers().contains(JavaModifier.STATIC)
            && field.getRawType().isEquivalentTo(Pattern.class)) {
          patterns.put(type.getName() + "#" + field.getName(), valueOf(field.reflect()));
        }
      }
    }
    return patterns;
  }

  private static Pattern valueOf(Field field) {
    try {
      field.setAccessible(true);
      return (Pattern) field.get(null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Cannot read " + field, e);
    }
  }

  /**
   * Whether {@code regex} holds a lazy quantifier, a group quantified without bound that holds a
   * quantifier, or two unbounded greedy quantifiers. Possessive quantifiers never backtrack and do
   * not count; escapes and character classes are skipped.
   */
  static boolean isRisky(String regex) {
    int unbounded = 0;
    List<Boolean> groups = new ArrayList<>();
    boolean quantifiedGroupHoldsQuantifier = false;
    boolean lastAtomWasGroupWithQuantifier = false;
    int i = 0;
    while (i < regex.length()) {
      char c = regex.charAt(i);
      boolean groupClosed = false;
      if (c == '\\') {
        i += 2;
      } else if (c == '[') {
        i = endOfClass(regex, i) + 1;
      } else if (c == '(') {
        groups.add(false);
        i += regex.startsWith("(?", i) ? groupPrefixLength(regex, i) : 1;
      } else if (c == ')') {
        boolean held = !groups.isEmpty() && groups.removeLast();
        lastAtomWasGroupWithQuantifier = held;
        groupClosed = true;
        if (held && !groups.isEmpty()) {
          groups.set(groups.size() - 1, true);
        }
        i++;
      } else if (c == '*' || c == '+' || c == '?' || c == '{') {
        int end = c == '{' ? regex.indexOf('}', i) : i;
        boolean isUnbounded = c == '*' || c == '+' || (c == '{' && regex.charAt(end - 1) == ',');
        char next = end + 1 < regex.length() ? regex.charAt(end + 1) : 0;
        if (next == '?') {
          return true;
        }
        if (next != '+') {
          if (isUnbounded) {
            unbounded++;
            if (lastAtomWasGroupWithQuantifier) {
              quantifiedGroupHoldsQuantifier = true;
            }
          }
          if (!groups.isEmpty()) {
            groups.set(groups.size() - 1, true);
          }
        }
        i = end + (next == '+' ? 2 : 1);
      } else {
        i++;
      }
      if (!groupClosed) {
        lastAtomWasGroupWithQuantifier = false;
      }
    }
    return quantifiedGroupHoldsQuantifier || unbounded >= 2;
  }

  private static int endOfClass(String regex, int start) {
    int i = start + 1;
    if (i < regex.length() && regex.charAt(i) == '^') {
      i++;
    }
    if (i < regex.length() && regex.charAt(i) == ']') {
      i++;
    }
    while (i < regex.length() && regex.charAt(i) != ']') {
      if (regex.charAt(i) == '\\') {
        i++;
      } else if (regex.charAt(i) == '[') {
        i = endOfClass(regex, i);
      }
      i++;
    }
    return i;
  }

  private static int groupPrefixLength(String regex, int start) {
    for (String prefix : List.of("(?:", "(?=", "(?!", "(?<=", "(?<!", "(?>")) {
      if (regex.startsWith(prefix, start)) {
        return prefix.length();
      }
    }
    if (regex.startsWith("(?<", start)) {
      return regex.indexOf('>', start) - start + 1;
    }
    int close = regex.indexOf(')', start);
    int colon = regex.indexOf(':', start);
    // an inline flag group: "(?m)" stands alone, "(?i:x)" opens a group
    return colon >= 0 && colon < close ? colon - start + 1 : close - start;
  }
}
