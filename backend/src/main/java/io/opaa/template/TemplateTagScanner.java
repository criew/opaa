package io.opaa.template;

import io.opaa.common.ValidationException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the placeholders of a template text written in the tag syntax of {@code docxtpl} ({@code {{
 * … }}}, {@code {% for … %}}, {@code {% if … %}}, with the paragraph and row modifiers {@code p},
 * {@code tr}, {@code tc}, {@code r}). It only reads the tags as text: nothing is evaluated, and no
 * template engine runs in the backend - filling happens in the sandbox alone.
 *
 * <p>Checks that every {@code for} and {@code if} is closed in order; anything else in a tag that
 * it does not understand is left to the filling script, which reports it there.
 */
final class TemplateTagScanner {

  static final int MAX_PLACEHOLDERS = 200;

  private static final Pattern TAG =
      Pattern.compile("\\{\\{(.*?)}}|\\{%(.*?)%}|\\{#.*?#}", Pattern.DOTALL);
  private static final Pattern MODIFIER = Pattern.compile("^(?:p|tr|tc|r)(?=\\s)");
  private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'|\"[^\"]*\"");
  private static final Pattern REFERENCE =
      Pattern.compile(
          "(\\|\\s*|\\bis\\s+(?:not\\s+)?)?([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)");
  private static final Pattern FOR =
      Pattern.compile(
          "for\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*))?\\s+in\\s+(.+)",
          Pattern.DOTALL);
  private static final Set<String> RESERVED =
      Set.of(
          "and", "or", "not", "in", "is", "if", "else", "true", "false", "none", "True", "False",
          "None", "loop", "range");

  private TemplateTagScanner() {}

  /** The placeholders of {@code text}, in the order they first appear. */
  static List<TemplatePlaceholder> scan(String text) {
    Collector collector = new Collector();
    Deque<Block> blocks = new ArrayDeque<>();
    Matcher tag = TAG.matcher(text);
    while (tag.find()) {
      if (tag.group(1) != null) {
        collector.references(stripModifier(tag.group(1)), PlaceholderKind.FIELD, blocks);
      } else if (tag.group(2) != null) {
        statement(stripModifier(tag.group(2)), collector, blocks);
      }
    }
    if (!blocks.isEmpty()) {
      throw new ValidationException(
          "Die Vorlage ist unvollständig: „{% "
              + blocks.peek().keyword()
              + " %}“ wird nicht mit „{% end"
              + blocks.peek().keyword()
              + " %}“ geschlossen.");
    }
    return collector.result();
  }

  private static void statement(String body, Collector collector, Deque<Block> blocks) {
    String keyword = body.split("\\s+", 2)[0];
    String rest = body.substring(keyword.length()).strip();
    switch (keyword) {
      case "for" -> {
        Matcher loop = FOR.matcher(body);
        if (!loop.matches()) {
          throw new ValidationException(
              "Die Vorlage enthält eine ungültige Schleife: „{% " + body + " %}“.");
        }
        String expression = loop.group(3).replaceAll("\\s+if\\s+.*$", "");
        String listName = collector.references(expression, PlaceholderKind.LIST, blocks);
        Set<String> variables = new LinkedHashSet<>();
        variables.add(loop.group(1));
        if (loop.group(2) != null) {
          variables.add(loop.group(2));
        }
        blocks.push(new Block("for", variables, listName));
      }
      case "if" -> {
        collector.references(rest, PlaceholderKind.CONDITION, blocks);
        blocks.push(new Block("if", Set.of(), null));
      }
      case "elif" -> {
        requireOpen("if", "elif", blocks);
        collector.references(rest, PlaceholderKind.CONDITION, blocks);
      }
      case "else" -> {
        if (blocks.isEmpty()) {
          throw new ValidationException(
              "Die Vorlage enthält „{% else %}“ ohne vorausgehendes „{% if %}“ oder „{% for %}“.");
        }
      }
      case "endfor", "endif" -> {
        String opened = keyword.substring(3);
        requireOpen(opened, keyword, blocks);
        blocks.pop();
      }
      default -> {
        // set, with, raw and the like are left to the filling script
      }
    }
  }

  private static void requireOpen(String opened, String closing, Deque<Block> blocks) {
    if (blocks.isEmpty() || !blocks.peek().keyword().equals(opened)) {
      throw new ValidationException(
          "Die Vorlage enthält „{% " + closing + " %}“ ohne passendes „{% " + opened + " %}“.");
    }
  }

  private static String stripModifier(String body) {
    String stripped = body.strip();
    Matcher modifier = MODIFIER.matcher(stripped);
    return modifier.find() ? stripped.substring(modifier.end()).strip() : stripped;
  }

  /** An open {@code for} or {@code if}; a loop names its variables and the list they run over. */
  private record Block(String keyword, Set<String> variables, String listName) {}

  private static final class Collector {

    private final Map<String, PlaceholderKind> kinds = new LinkedHashMap<>();
    private final Map<String, Set<String>> fields = new LinkedHashMap<>();

    /**
     * Records the references of {@code expression} and returns the first top-level name it names,
     * or {@code null}.
     */
    String references(String expression, PlaceholderKind kind, Deque<Block> blocks) {
      String first = null;
      String sanitized = STRING_LITERAL.matcher(expression).replaceAll(" ");
      Matcher reference = REFERENCE.matcher(sanitized);
      while (reference.find()) {
        if (reference.group(1) != null) {
          continue;
        }
        int start = reference.start(2);
        if (start > 0 && sanitized.charAt(start - 1) == '.') {
          continue;
        }
        String[] parts = reference.group(2).split("\\.");
        if (RESERVED.contains(parts[0])) {
          continue;
        }
        Block loop = loopOf(parts[0], blocks);
        if (loop != null) {
          if (parts.length > 1 && loop.listName() != null) {
            fields.computeIfAbsent(loop.listName(), name -> new LinkedHashSet<>()).add(parts[1]);
          }
          continue;
        }
        record(parts[0], kind);
        if (parts.length > 1) {
          fields.computeIfAbsent(parts[0], name -> new LinkedHashSet<>()).add(parts[1]);
        }
        if (first == null) {
          first = parts[0];
        }
      }
      return first;
    }

    private static Block loopOf(String name, Deque<Block> blocks) {
      for (Block block : blocks) {
        if (block.variables().contains(name)) {
          return block;
        }
      }
      return null;
    }

    private void record(String name, PlaceholderKind kind) {
      PlaceholderKind known = kinds.get(name);
      if (known == null) {
        if (kinds.size() >= MAX_PLACEHOLDERS) {
          throw new ValidationException(
              "Die Vorlage enthält mehr als " + MAX_PLACEHOLDERS + " Platzhalter.");
        }
        kinds.put(name, kind);
      } else if (rank(kind) > rank(known)) {
        kinds.put(name, kind);
      }
    }

    private static int rank(PlaceholderKind kind) {
      return switch (kind) {
        case CONDITION -> 0;
        case FIELD -> 1;
        case LIST -> 2;
      };
    }

    List<TemplatePlaceholder> result() {
      List<TemplatePlaceholder> placeholders = new ArrayList<>(kinds.size());
      kinds.forEach(
          (name, kind) ->
              placeholders.add(
                  new TemplatePlaceholder(
                      name, kind, List.copyOf(fields.getOrDefault(name, Set.of())))));
      return placeholders;
    }
  }
}
