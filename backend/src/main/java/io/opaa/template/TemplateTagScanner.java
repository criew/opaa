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

  /** Fields across all lists; each one is stored and sent with every template answer. */
  static final int MAX_FIELDS = 1000;

  /** The text a template may hold; a Word document of normal size stays far below. */
  static final int MAX_TEXT_CHARS = 2_000_000;

  private static final Pattern MODIFIER = Pattern.compile("^(?:p|tr|tc|r)(?=\\s+[a-z])");
  private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'|\"[^\"]*\"");
  private static final Pattern REFERENCE =
      Pattern.compile(
          "(\\|\\s*|\\bis\\s+(?:not\\s+)?)?([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)");
  private static final Pattern FOR =
      Pattern.compile(
          "for\\s+\\(?\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*))?\\s*\\)?"
              + "\\s+in\\s+(.+)",
          Pattern.DOTALL);

  /**
   * What would let a value reach the filled document as raw Word XML: the filters and the global
   * that mark text as markup, also by name in {@code map}/{@code select}, and the autoescape block.
   * The filling script refuses them too; refusing here tells the author at upload.
   */
  private static final Pattern RAW_MARKUP =
      Pattern.compile(
          "(?:\\|\\s*|^filter\\s+)(safe|urlize|xmlattr)\\b|['\"](safe|urlize|xmlattr)['\"]"
              + "|\\b(lipsum)\\b|^(autoescape)\\b");

  private static final Set<String> RESERVED =
      Set.of(
          "and", "or", "not", "in", "is", "if", "else", "true", "false", "none", "True", "False",
          "None", "loop", "range");

  private TemplateTagScanner() {}

  /**
   * The placeholders of {@code text}, in the order they first appear. Reads in one pass, linear in
   * the length of the text: an opening without its closing ends the scan, it is never retried.
   */
  static List<TemplatePlaceholder> scan(String text) {
    if (text.length() > MAX_TEXT_CHARS) {
      throw new ValidationException("Die Vorlage enthält zu viel Text für eine Vorlage.");
    }
    Collector collector = new Collector();
    Deque<Block> blocks = new ArrayDeque<>();
    boolean raw = false;
    int from = 0;
    while (true) {
      int open = text.indexOf('{', from);
      if (open < 0 || open + 1 >= text.length()) {
        break;
      }
      char kind = text.charAt(open + 1);
      String closing =
          switch (kind) {
            case '{' -> "}}";
            case '%' -> "%}";
            case '#' -> "#}";
            default -> null;
          };
      if (closing == null) {
        from = open + 1;
        continue;
      }
      int close = text.indexOf(closing, open + 2);
      if (close < 0) {
        break;
      }
      String body = stripModifier(text.substring(open + 2, close));
      from = close + 2;
      if (raw) {
        raw = !(kind == '%' && body.equals("endraw"));
        continue;
      }
      if (kind != '#') {
        requireNoRawMarkup(body);
      }
      if (kind == '{') {
        collector.references(body, PlaceholderKind.FIELD, blocks);
      } else if (kind == '%') {
        raw = body.equals("raw");
        statement(body, collector, blocks);
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

  private static void requireNoRawMarkup(String body) {
    Matcher markup = RAW_MARKUP.matcher(body);
    if (markup.find()) {
      String name = markup.group(1);
      for (int group = 2; name == null; group++) {
        name = markup.group(group);
      }
      throw new ValidationException(
          "Die Vorlage nutzt „"
              + name
              + "“. Vorlagen dürfen Werte nur als Text einsetzen; safe, urlize, xmlattr, lipsum und"
              + " autoescape sind nicht erlaubt.");
    }
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
        String[] source = loop.group(3).split("\\s+if\\s+", 2);
        String listName =
            source[0].strip().startsWith("range")
                ? null
                : collector.references(source[0], PlaceholderKind.LIST, blocks);
        if (listName == null) {
          collector.references(source[0], PlaceholderKind.FIELD, blocks);
        }
        Set<String> variables = new LinkedHashSet<>();
        variables.add(loop.group(1));
        if (loop.group(2) != null) {
          variables.add(loop.group(2));
        }
        Block block = new Block("for", variables, listName);
        blocks.push(block);
        if (source.length > 1) {
          collector.references(source[1], PlaceholderKind.CONDITION, blocks);
        }
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

  /**
   * The tag body without Jinja's whitespace control ({@code -}, {@code +}) and docxtpl modifier.
   */
  private static String stripModifier(String body) {
    String stripped = body.strip();
    if (stripped.startsWith("-") || stripped.startsWith("+")) {
      stripped = stripped.substring(1).strip();
    }
    if (stripped.endsWith("-") || stripped.endsWith("+")) {
      stripped = stripped.substring(0, stripped.length() - 1).strip();
    }
    Matcher modifier = MODIFIER.matcher(stripped);
    return modifier.find() ? stripped.substring(modifier.end()).strip() : stripped;
  }

  /** An open {@code for} or {@code if}; a loop names its variables and the list they run over. */
  private record Block(String keyword, Set<String> variables, String listName) {}

  private static final class Collector {

    private final Map<String, PlaceholderKind> kinds = new LinkedHashMap<>();
    private final Map<String, Set<String>> fields = new LinkedHashMap<>();
    private int fieldCount;

    private void field(String list, String name) {
      if (fields.computeIfAbsent(list, key -> new LinkedHashSet<>()).add(name)
          && ++fieldCount > MAX_FIELDS) {
        throw new ValidationException(
            "Die Vorlage enthält mehr als " + MAX_FIELDS + " Felder in Listen.");
      }
    }

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
            field(loop.listName(), parts[1]);
          }
          continue;
        }
        record(parts[0], kind);
        if (parts.length > 1) {
          field(parts[0], parts[1]);
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
