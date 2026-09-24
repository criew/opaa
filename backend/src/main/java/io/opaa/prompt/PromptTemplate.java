package io.opaa.prompt;

import io.opaa.api.types.PromptVariableType;
import io.opaa.common.ValidationException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The variable syntax of a prompt text and the rules its definitions follow
 * (docs/features/spaces-and-assets.md#prompt-bibliothek). A placeholder is exactly {@code {{name}}}
 * - no blanks, a name of letters, digits and underscores starting with a letter; any other {@code
 * {{...}}} is refused rather than left as text, so the syntax stays unambiguous. The text and the
 * definitions must agree in both directions; the {@link #SYSTEM_VARIABLES} are resolved on
 * insertion and never defined.
 */
public final class PromptTemplate {

  /** Resolved when the prompt is inserted - the date of that day, the inserting person's name. */
  public static final Set<String> SYSTEM_VARIABLES = Set.of("CURRENT_DATE", "USER_NAME");

  static final int MAX_TEXT_LENGTH = 8000;
  static final int MAX_VARIABLES = 20;
  static final int MAX_OPTIONS = 50;
  private static final int MAX_NAME_LENGTH = 64;
  private static final int MAX_LABEL_LENGTH = 255;
  private static final int MAX_OPTION_LENGTH = 255;
  private static final int MAX_DEFAULT_LENGTH = 2000;

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(.*?)}}", Pattern.DOTALL);
  private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

  private PromptTemplate() {}

  /**
   * The names the text uses as placeholders, in order of first use, system variables included.
   *
   * @throws ValidationException for a {@code {{...}}} that is no valid placeholder.
   */
  public static Set<String> placeholders(String text) {
    Set<String> names = new LinkedHashSet<>();
    Matcher matcher = PLACEHOLDER.matcher(text);
    while (matcher.find()) {
      String name = matcher.group(1);
      if (!VARIABLE_NAME.matcher(name).matches() || name.length() > MAX_NAME_LENGTH) {
        throw new ValidationException(
            "Ungültiger Platzhalter „{{"
                + name
                + "}}“: Ein Variablenname beginnt mit einem Buchstaben und enthält nur Buchstaben,"
                + " Ziffern und Unterstriche, ohne Leerzeichen.");
      }
      names.add(name);
    }
    return names;
  }

  /**
   * Refuses a text and definitions that do not fit together: a malformed placeholder, an invalid
   * definition, a placeholder without definition, a definition the text does not use.
   */
  public static void validate(String text, List<PromptVariable> variables) {
    if (variables.size() > MAX_VARIABLES) {
      throw new ValidationException(
          "Ein Prompt kann höchstens " + MAX_VARIABLES + " Variablen haben.");
    }
    Set<String> defined = new HashSet<>();
    for (PromptVariable variable : variables) {
      validateDefinition(variable);
      if (!defined.add(variable.name())) {
        throw new ValidationException(
            "Die Variable „" + variable.name() + "“ ist mehrfach definiert.");
      }
    }
    Set<String> used = placeholders(text);
    for (String name : used) {
      if (!SYSTEM_VARIABLES.contains(name) && !defined.contains(name)) {
        throw new ValidationException(
            "Die Variable „{{" + name + "}}“ wird im Text verwendet, ist aber nicht definiert.");
      }
    }
    for (PromptVariable variable : variables) {
      if (!used.contains(variable.name())) {
        throw new ValidationException(
            "Die Variable „"
                + variable.name()
                + "“ ist definiert, wird im Text aber nicht verwendet.");
      }
    }
  }

  private static void validateDefinition(PromptVariable variable) {
    if (variable == null) {
      throw new ValidationException("Eine Variablendefinition ist leer.");
    }
    String name = variable.name();
    if (name == null || name.length() > MAX_NAME_LENGTH || !VARIABLE_NAME.matcher(name).matches()) {
      throw new ValidationException(
          "Ungültiger Variablenname „"
              + name
              + "“: Er beginnt mit einem Buchstaben und enthält nur Buchstaben, Ziffern und"
              + " Unterstriche, höchstens "
              + MAX_NAME_LENGTH
              + " Zeichen.");
    }
    if (SYSTEM_VARIABLES.contains(name.toUpperCase(Locale.ROOT))) {
      throw new ValidationException(
          "„"
              + name
              + "“ ist eine Systemvariable und wird beim Einsetzen aufgelöst; sie kann nicht"
              + " definiert werden.");
    }
    if (variable.label() == null
        || variable.label().isBlank()
        || variable.label().length() > MAX_LABEL_LENGTH) {
      throw new ValidationException(
          "Die Variable „"
              + name
              + "“ braucht eine Beschriftung mit höchstens "
              + MAX_LABEL_LENGTH
              + " Zeichen.");
    }
    if (variable.type() == null) {
      throw new ValidationException("Die Variable „" + name + "“ braucht einen Typ.");
    }
    if (variable.type() == PromptVariableType.SELECT) {
      validateOptions(variable);
    } else if (!variable.options().isEmpty()) {
      throw new ValidationException(
          "Auswahlwerte sind nur für eine Auswahl zulässig, nicht für die Variable „"
              + name
              + "“.");
    }
    validateDefault(variable);
  }

  private static void validateOptions(PromptVariable variable) {
    List<String> options = variable.options();
    if (options.isEmpty() || options.size() > MAX_OPTIONS) {
      throw new ValidationException(
          "Die Auswahl „"
              + variable.name()
              + "“ braucht zwischen 1 und "
              + MAX_OPTIONS
              + " Auswahlwerte.");
    }
    Set<String> seen = new HashSet<>();
    for (String option : options) {
      if (option == null || option.isBlank() || option.length() > MAX_OPTION_LENGTH) {
        throw new ValidationException(
            "Ein Auswahlwert der Variable „"
                + variable.name()
                + "“ ist leer oder länger als "
                + MAX_OPTION_LENGTH
                + " Zeichen.");
      }
      if (!seen.add(option)) {
        throw new ValidationException(
            "Der Auswahlwert „"
                + option
                + "“ steht mehrfach in der Variable „"
                + variable.name()
                + "“.");
      }
    }
  }

  private static void validateDefault(PromptVariable variable) {
    String value = variable.defaultValue();
    if (value == null) {
      return;
    }
    if (value.length() > MAX_DEFAULT_LENGTH) {
      throw new ValidationException(
          "Die Vorbelegung der Variable „"
              + variable.name()
              + "“ ist länger als "
              + MAX_DEFAULT_LENGTH
              + " Zeichen.");
    }
    if (variable.type() == PromptVariableType.SELECT && !variable.options().contains(value)) {
      throw new ValidationException(
          "Die Vorbelegung der Auswahl „" + variable.name() + "“ ist keiner ihrer Auswahlwerte.");
    }
    if (variable.type() == PromptVariableType.DATE) {
      try {
        LocalDate.parse(value);
      } catch (DateTimeParseException invalid) {
        throw new ValidationException(
            "Die Vorbelegung der Datumsvariable „"
                + variable.name()
                + "“ ist kein Datum im Format JJJJ-MM-TT.");
      }
    }
  }
}
