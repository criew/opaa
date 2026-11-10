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
 * <p>Checks that every {@code for} and {@code if} is closed in order, and that a tag uses only what
 * fields, lists and conditions need: the statements of {@link #STATEMENTS}, the filters of {@link
 * #FILTERS} and the tests of {@link #TESTS} with constant arguments, no call of a function or
 * method, no assignment, no slice, no dict and no name, attribute or key that starts with an
 * underscore. The filling script checks the same on Jinja's syntax tree; refusing here tells the
 * author at upload.
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

  /** The statements a template may use; docxtpl's vm, hm, colspan and cellbg among them. */
  static final Set<String> STATEMENTS =
      Set.of(
          "for", "endfor", "if", "elif", "else", "endif", "raw", "endraw", "vm", "hm", "colspan",
          "cellbg");

  /** Filters that give text, a number or a list of values and never mark anything as markup. */
  static final List<String> FILTERS =
      List.of(
          "default",
          "d",
          "upper",
          "lower",
          "title",
          "capitalize",
          "trim",
          "round",
          "int",
          "float",
          "abs",
          "join",
          "length",
          "count",
          "first",
          "last");

  static final List<String> TESTS =
      List.of(
          "defined",
          "undefined",
          "none",
          "number",
          "string",
          "boolean",
          "true",
          "false",
          "odd",
          "even",
          "divisibleby",
          "eq",
          "ne",
          "lt",
          "le",
          "gt",
          "ge",
          "in");

  /** Words of the expression syntax; a parenthesis after one of them is no call. */
  private static final Set<String> WORDS =
      Set.of(
          "and", "or", "not", "in", "is", "if", "else", "for", "true", "false", "none", "True",
          "False", "None");

  private static final Set<String> CONSTANT_WORDS =
      Set.of("true", "false", "none", "True", "False", "None");

  private static final Set<String> FORBIDDEN_NAMES = Set.of("self", "caller", "varargs", "kwargs");

  private static final Pattern BLANK = Pattern.compile("\\s*", Pattern.UNICODE_CHARACTER_CLASS);

  private static final Pattern TOKEN =
      Pattern.compile(
          "\\s*(?:(?<string>'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\")"
              + "|(?<number>\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)"
              + "|(?<name>[\\p{L}_][\\p{L}\\p{N}_]*)"
              + "|(?<op>\\*\\*|//|==|!=|<=|>=|[-+*/%~<>()\\[\\],.|=:{}]))",
          Pattern.UNICODE_CHARACTER_CLASS);

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
      if (kind == '%') {
        requireStatement(body);
      }
      if (kind != '#') {
        requireAllowed(body);
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

  private static void requireStatement(String body) {
    String keyword = body.split("\\s+", 2)[0];
    if (!STATEMENTS.contains(keyword)) {
      throw notAllowed("{% " + keyword + " %}");
    }
  }

  /** Refuses a tag body that uses anything beyond fields, lists, conditions and the allowlists. */
  private static void requireAllowed(String body) {
    List<Token> tokens = tokens(body);
    for (int i = 0; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      Token before = i > 0 ? tokens.get(i - 1) : null;
      if (token.name()) {
        if (is(before, "|")) {
          if (!FILTERS.contains(token.text())) {
            throw notAllowed("den Filter " + token.text());
          }
          i = constantArguments(tokens, i);
        } else if (token.text().equals("not") && isName(before, "is")) {
          continue;
        } else if (isName(before, "is")
            || (isName(before, "not") && isName(at(tokens, i - 2), "is"))) {
          if (!TESTS.contains(token.text())) {
            throw notAllowed("den Test " + token.text());
          }
          i = constantArguments(tokens, i);
        } else if (token.text().startsWith("_")
            || (!is(before, ".") && FORBIDDEN_NAMES.contains(token.text()))) {
          throw notAllowed(token.text());
        }
      } else if (is(token, "(") && callable(before)) {
        throw notAllowed("einen Funktionsaufruf");
      } else if (is(token, "[") && callable(before)) {
        Token key = at(tokens, i + 1);
        if (key == null
            || !(key.kind().equals("number") || key.kind().equals("string"))
            || key.text().substring(key.kind().equals("string") ? 1 : 0).startsWith("_")
            || !is(at(tokens, i + 2), "]")) {
          throw notAllowed("einen Schlüssel, der kein fester Wert ist");
        }
        i += 2;
      } else if (Set.of("{", "}", ":", "=", "**").contains(token.text())
          && token.kind().equals("op")) {
        throw notAllowed(token.text());
      }
    }
  }

  /** The index of the closing parenthesis of a filter's or test's arguments, all constants. */
  private static int constantArguments(List<Token> tokens, int name) {
    if (!is(at(tokens, name + 1), "(")) {
      return name;
    }
    for (int i = name + 2; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (is(token, ")")) {
        return i;
      }
      boolean constant =
          token.kind().equals("number")
              || (token.kind().equals("string") && !token.text().substring(1).startsWith("_"))
              || (token.name() && CONSTANT_WORDS.contains(token.text()))
              || is(token, ",")
              || is(token, "-");
      if (!constant) {
        throw notAllowed(
            "ein Argument von " + tokens.get(name).text() + ", das kein fester Wert ist");
      }
    }
    return tokens.size();
  }

  /** Whether a parenthesis or bracket after {@code token} calls or indexes it. */
  private static boolean callable(Token token) {
    return token != null
        && ((token.name() && !WORDS.contains(token.text()))
            || token.kind().equals("string")
            || is(token, ")")
            || is(token, "]"));
  }

  private static List<Token> tokens(String body) {
    List<Token> tokens = new ArrayList<>();
    Matcher matcher = TOKEN.matcher(body);
    int position = 0;
    while (position < body.length()) {
      if (BLANK.matcher(body).region(position, body.length()).matches()) {
        break;
      }
      if (!matcher.find(position) || matcher.start() != position) {
        throw notAllowed("das Zeichen " + body.charAt(firstNonBlank(body, position)));
      }
      for (String kind : List.of("string", "number", "name", "op")) {
        if (matcher.group(kind) != null) {
          tokens.add(new Token(kind, matcher.group(kind)));
        }
      }
      position = matcher.end();
    }
    return tokens;
  }

  private static int firstNonBlank(String text, int from) {
    int i = from;
    while (i < text.length() - 1 && Character.isWhitespace(text.charAt(i))) {
      i++;
    }
    return i;
  }

  private static Token at(List<Token> tokens, int index) {
    return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
  }

  private static boolean is(Token token, String operator) {
    return token != null && token.kind().equals("op") && token.text().equals(operator);
  }

  private static boolean isName(Token token, String name) {
    return token != null && token.name() && token.text().equals(name);
  }

  private static ValidationException notAllowed(String what) {
    return new ValidationException(
        "Die Vorlage nutzt „"
            + what
            + "“. Vorlagen setzen Werte nur als Text ein und kennen nur Felder, Listen und"
            + " Bedingungen mit den Filtern "
            + String.join(", ", FILTERS)
            + "; Funktionsaufrufe, set, macro, autoescape und Ähnliches sind nicht erlaubt.");
  }

  /** One token of a tag body: a string, a number, a name or an operator. */
  private record Token(String kind, String text) {

    boolean name() {
      return kind.equals("name");
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
        String listName = collector.references(source[0], PlaceholderKind.LIST, blocks);
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
