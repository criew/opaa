package io.opaa.linktarget;

import io.opaa.common.ValidationException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates and normalizes the origins of the allowed link targets: scheme {@code https} or {@code
 * http}, an ASCII host (an internationalized one in its {@code xn--} form, as the browser's {@code
 * URL.origin} reports it) and an optional port, nothing else. The host is lower-cased and a default
 * port is dropped, so two spellings of one origin are one entry. Messages are German, they reach
 * the administration surface.
 */
public final class LinkTargetOrigins {

  /** Upper bound of the list; the table's check enforces it as well. */
  public static final int MAX_ORIGINS = 50;

  /** Upper bound of one entry, scheme and port included. */
  public static final int MAX_LENGTH = 300;

  private LinkTargetOrigins() {}

  /** The normalized, de-duplicated list in input order; throws on the first invalid entry. */
  public static List<String> normalizeAll(List<String> origins) {
    if (origins == null) {
      return List.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String origin : origins) {
      if (origin == null || origin.isBlank()) {
        continue;
      }
      normalized.add(normalize(origin));
    }
    if (normalized.size() > MAX_ORIGINS) {
      throw new ValidationException(
          "Es sind höchstens " + MAX_ORIGINS + " erlaubte Linkziele möglich.");
    }
    return List.copyOf(new ArrayList<>(normalized));
  }

  /** One origin in its normalized form ({@code https://intranet.stadt.example}). */
  public static String normalize(String origin) {
    String input = origin.strip();
    if (input.length() > MAX_LENGTH) {
      throw invalid(input, "Der Eintrag ist länger als " + MAX_LENGTH + " Zeichen.");
    }
    if (input.contains("*")) {
      throw invalid(input, "Platzhalter wie * sind nicht erlaubt.");
    }
    if (!input.chars().allMatch(c -> c < 0x80)) {
      throw invalid(
          input,
          "Nur ASCII-Zeichen sind erlaubt; einen internationalisierten Host bitte in der Form"
              + " xn--… eintragen.");
    }
    URI uri;
    try {
      uri = new URI(input);
    } catch (URISyntaxException e) {
      throw invalid(input, "Das ist keine gültige Adresse.");
    }
    String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"https".equals(scheme) && !"http".equals(scheme)) {
      throw invalid(input, "Erlaubt sind nur Adressen mit https:// oder http://.");
    }
    if (uri.getRawUserInfo() != null) {
      throw invalid(input, "Benutzername oder Passwort in der Adresse sind nicht erlaubt.");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw invalid(input, "Die Adresse nennt keinen Host.");
    }
    String path = uri.getRawPath();
    if ((path != null && !path.isEmpty() && !"/".equals(path))
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw invalid(
          input, "Nur Schema, Host und Port sind erlaubt, ohne Pfad, Abfrage oder Anker.");
    }
    if (uri.getHost().startsWith("[")) {
      throw invalid(input, "IPv6-Adressen sind als Linkziel nicht erlaubt.");
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    int port = uri.getPort();
    if (port == 0 || port > 65535) {
      throw invalid(input, "Der Port muss zwischen 1 und 65535 liegen.");
    }
    boolean defaultPort =
        port == -1
            || ("https".equals(scheme) && port == 443)
            || ("http".equals(scheme) && port == 80);
    return scheme + "://" + host + (defaultPort ? "" : ":" + port);
  }

  private static ValidationException invalid(String input, String reason) {
    return new ValidationException("Ungültiges Linkziel „" + input + "“: " + reason);
  }
}
