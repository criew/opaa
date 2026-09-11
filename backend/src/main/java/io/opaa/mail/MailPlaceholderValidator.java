package io.opaa.mail;

import io.opaa.common.ValidationException;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that an edited template only references placeholders its {@link MailTemplateKey} declares
 * (#1536, ADR-0033 Entscheidung 10).
 *
 * <p><b>Why this exists at edit time and not only at render time:</b> rendering is strict, so a
 * typo in a placeholder name turns into a failed send - and the send that fails is somebody's
 * invitation or password reset, discovered by the person waiting for it. The same check, run when
 * the text is saved, turns that into a 400 in front of the administrator who introduced it.
 *
 * <p>Only ordinary variable tags ({@code {{ name }}}) count as references. Comments ({@code
 * {{!…}}}), partials ({@code {{>…}}}), section markers ({@code {{#…}}}, {@code {{/…}}}, {@code
 * {{^…}}}), delimiter changes ({@code {{=…}}}) and the unescaped forms ({@code {{{…}}}}, {@code
 * {{&…}}}) are template structure, not variables to bind.
 */
public final class MailPlaceholderValidator {

  /** A triple-stache (skipped), or an ordinary double-stache whose inner text is captured. */
  private static final Pattern TAG =
      Pattern.compile("\\{\\{\\{\\s*[^{}]*?\\s*\\}\\}\\}|\\{\\{\\s*([^{}]*?)\\s*\\}\\}");

  /** Leading characters that mark a tag as structure rather than a bound variable. */
  private static final String NON_VARIABLE_PREFIXES = "!>#/^&=";

  private MailPlaceholderValidator() {}

  /** The placeholder names referenced in {@code content}, sorted; empty for {@code null}. */
  public static SortedSet<String> referencedPlaceholders(String content) {
    SortedSet<String> references = new TreeSet<>();
    if (content == null || content.isEmpty()) {
      return references;
    }
    Matcher matcher = TAG.matcher(content);
    while (matcher.find()) {
      String inner = matcher.group(1);
      if (inner == null) {
        continue;
      }
      inner = inner.trim();
      if (inner.isEmpty() || NON_VARIABLE_PREFIXES.indexOf(inner.charAt(0)) >= 0) {
        continue;
      }
      references.add(inner);
    }
    return references;
  }

  /**
   * Rejects {@code content} that references a placeholder {@code key} does not declare, with a
   * message naming the field, the unknown names and the accepted ones - {@code
   * GlobalExceptionHandler} turns it into a 400 with that text.
   *
   * @param fieldLabel the request field the content came from, e.g. {@code bodyPlain}
   */
  public static void requireDeclaredPlaceholders(
      MailTemplateKey key, String fieldLabel, String content) {
    Set<String> declared = Set.copyOf(key.placeholders());
    SortedSet<String> unknown = new TreeSet<>();
    for (String reference : referencedPlaceholders(content)) {
      if (!declared.contains(reference)) {
        unknown.add(reference);
      }
    }
    if (unknown.isEmpty()) {
      return;
    }
    throw new ValidationException(
        fieldLabel
            + ": Unbekannte Platzhalter "
            + braced(unknown)
            + ". Erlaubt sind "
            + braced(key.placeholders()));
  }

  private static String braced(Iterable<String> names) {
    List<String> braced = new java.util.ArrayList<>();
    for (String name : names) {
      braced.add("{{" + name + "}}");
    }
    return String.join(", ", braced);
  }
}
