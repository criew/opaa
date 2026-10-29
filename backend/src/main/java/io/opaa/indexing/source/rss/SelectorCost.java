package io.opaa.indexing.source.rss;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import org.jsoup.select.Evaluator;

/**
 * Judges a content selector by the evaluator tree Jsoup parsed from it - the very tree a run
 * evaluates - never by its text, so quoting, escaping or case cannot hide a part. Only type, id,
 * class and attribute tests joined by descendant, child or comma pass: each costs at most a walk up
 * the ancestors per element, which Jsoup memoizes. Jsoup keeps the tree in package-private fields;
 * a Jsoup version that moves them fails every check here, closed.
 */
final class SelectorCost {

  /** What a selector costs: combinators on its longest group and the number of groups. */
  record Shape(int combinators, int groups) {}

  /** Thrown for a part outside the allowed forms; {@code regex} for a regular expression. */
  static final class Refused extends RuntimeException {
    final boolean regex;

    Refused(boolean regex) {
      super(null, null, false, false);
      this.regex = regex;
    }
  }

  private static final Set<String> LEAVES =
      Set.of(
          "Evaluator$Tag",
          "Evaluator$TagStartsWith",
          "Evaluator$TagEndsWith",
          "Evaluator$Id",
          "Evaluator$Class",
          "Evaluator$Attribute",
          "Evaluator$AttributeStarting",
          "Evaluator$AttributeWithValue",
          "Evaluator$AttributeWithValueNot",
          "Evaluator$AttributeWithValueStarting",
          "Evaluator$AttributeWithValueEnding",
          "Evaluator$AttributeWithValueContaining",
          "Evaluator$AllElements");

  private static final Set<String> REGULAR_EXPRESSIONS =
      Set.of(
          "Evaluator$Matches",
          "Evaluator$MatchesOwn",
          "Evaluator$MatchesWholeText",
          "Evaluator$MatchesWholeOwnText",
          "Evaluator$AttributeWithValueMatching",
          "Evaluator$MatchText");

  private static final Field COMBINED = field("org.jsoup.select.CombiningEvaluator", "evaluators");
  private static final Field INNER = field("org.jsoup.select.StructuralEvaluator", "evaluator");
  private static final Field PARENT_RUN =
      field("org.jsoup.select.StructuralEvaluator$ImmediateParentRun", "evaluators");

  private SelectorCost() {}

  /**
   * The shape of {@code root}.
   *
   * @throws Refused for any evaluator outside the allowed forms
   */
  static Shape of(Evaluator root) {
    if (COMBINED == null || INNER == null || PARENT_RUN == null) {
      throw new Refused(false);
    }
    String kind = kind(root);
    if (kind.equals("CombiningEvaluator$Or")) {
      int longest = 0;
      List<Evaluator> groups = children(COMBINED, root);
      for (Evaluator group : groups) {
        longest = Math.max(longest, combinators(group));
      }
      return new Shape(longest, groups.size());
    }
    return new Shape(combinators(root), 1);
  }

  private static int combinators(Evaluator evaluator) {
    String kind = kind(evaluator);
    if (LEAVES.contains(kind)) {
      return 0;
    }
    if (REGULAR_EXPRESSIONS.contains(kind)) {
      throw new Refused(true);
    }
    return switch (kind) {
      case "CombiningEvaluator$And" -> sum(children(COMBINED, evaluator));
      case "StructuralEvaluator$Ancestor" -> 1 + combinators(inner(evaluator));
      case "StructuralEvaluator$ImmediateParentRun" -> {
        List<Evaluator> run = children(PARENT_RUN, evaluator);
        yield run.size() - 1 + sum(run);
      }
      default -> throw new Refused(false);
    };
  }

  private static int sum(List<Evaluator> evaluators) {
    int total = 0;
    for (Evaluator evaluator : evaluators) {
      total += combinators(evaluator);
    }
    return total;
  }

  private static String kind(Evaluator evaluator) {
    String name = evaluator.getClass().getName();
    return name.startsWith("org.jsoup.select.")
        ? name.substring("org.jsoup.select.".length())
        : name;
  }

  @SuppressWarnings("unchecked")
  private static List<Evaluator> children(Field field, Evaluator evaluator) {
    try {
      return (List<Evaluator>) field.get(evaluator);
    } catch (IllegalAccessException | IllegalArgumentException e) {
      throw new Refused(false);
    }
  }

  private static Evaluator inner(Evaluator evaluator) {
    try {
      return (Evaluator) INNER.get(evaluator);
    } catch (IllegalAccessException | IllegalArgumentException e) {
      throw new Refused(false);
    }
  }

  private static Field field(String type, String name) {
    try {
      Field field = Class.forName(type).getDeclaredField(name);
      field.setAccessible(true);
      return field;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }
}
