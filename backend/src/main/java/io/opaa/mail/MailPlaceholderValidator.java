package io.opaa.mail;

import io.opaa.common.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides what an administrator may put into a mail template (#1536, ADR-0033 Entscheidung 10):
 * plain variable tags from the key's declared set, comments, and nothing else.
 *
 * <p>The check runs when the text is saved, not when it is sent, because the send that would fail
 * is somebody's invitation - discovered by the person waiting for it. {@link #requireSupportedTags}
 * additionally closes the two holes a placeholder check alone leaves: an unescaped tag ({@code
 * {{{x}}}}, {@code {{&x}}}) would put raw HTML from a variable into the message, and a section,
 * partial or delimiter change would render nothing this subsystem can supply.
 */
public final class MailPlaceholderValidator {

  /** A triple-stache (captured separately), or an ordinary double-stache. */
  private static final Pattern TAG =
      Pattern.compile("(\\{\\{\\{\\s*[^{}]*?\\s*\\}\\}\\})|\\{\\{\\s*([^{}]*?)\\s*\\}\\}");

  /** Tag prefixes that are neither a plain variable nor a comment. */
  private static final String UNSUPPORTED_PREFIXES = ">#/^&=";

  private MailPlaceholderValidator() {}

  /**
   * The plain variable names referenced in {@code content}, sorted. Comments and every unsupported
   * tag form are skipped here - {@link #requireSupportedTags} is what rejects those.
   */
  public static SortedSet<String> referencedPlaceholders(String content) {
    SortedSet<String> references = new TreeSet<>();
    if (content == null || content.isEmpty()) {
      return references;
    }
    Matcher matcher = TAG.matcher(content);
    while (matcher.find()) {
      String inner = matcher.group(2);
      if (inner == null) {
        continue;
      }
      inner = inner.trim();
      if (inner.isEmpty()
          || inner.charAt(0) == '!'
          || UNSUPPORTED_PREFIXES.indexOf(inner.charAt(0)) >= 0) {
        continue;
      }
      references.add(inner);
    }
    return references;
  }

  /**
   * Rejects everything but plain variable tags and comments, naming the field. An unescaped tag is
   * refused even for a declared name: {@code {{{displayName}}}} would place a value into the HTML
   * part without escaping.
   */
  public static void requireSupportedTags(String fieldLabel, String content) {
    if (content == null || content.isEmpty()) {
      return;
    }
    Matcher matcher = TAG.matcher(content);
    while (matcher.find()) {
      if (matcher.group(1) != null) {
        throw unsupported(fieldLabel, matcher.group(1));
      }
      String inner = matcher.group(2).trim();
      if (!inner.isEmpty() && UNSUPPORTED_PREFIXES.indexOf(inner.charAt(0)) >= 0) {
        throw unsupported(fieldLabel, matcher.group());
      }
    }
  }

  /**
   * Rejects {@code content} that references a placeholder {@code key} does not declare, with a
   * message naming the field, the unknown names and the accepted ones.
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

  private static ValidationException unsupported(String fieldLabel, String tag) {
    return new ValidationException(
        fieldLabel
            + ": Nicht unterstützte Vorlagen-Syntax "
            + tag.trim()
            + ". Erlaubt sind nur einfache Platzhalter der Form {{name}} und Kommentare"
            + " der Form {{! ... }}");
  }

  private static String braced(Iterable<String> names) {
    List<String> braced = new ArrayList<>();
    for (String name : names) {
      braced.add("{{" + name + "}}");
    }
    return String.join(", ", braced);
  }
}
