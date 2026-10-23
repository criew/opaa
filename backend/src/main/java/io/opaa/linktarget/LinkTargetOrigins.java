package io.opaa.linktarget;

import io.opaa.common.ValidationException;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates and normalizes the origins of the allowed link targets: scheme {@code https} or {@code
 * http}, a host and an optional port, nothing else. The host is lower-cased and in ASCII form, a
 * default port is dropped, so two spellings of one origin are one entry. Messages are German, they
 * reach the administration surface.
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
    URI uri;
    try {
      uri = new URI(withAsciiHost(input));
    } catch (URISyntaxException | IllegalArgumentException e) {
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
    String host;
    try {
      host = IDN.toASCII(uri.getHost(), IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      throw invalid(input, "Der Host ist ungültig.");
    }
    int port = uri.getPort();
    boolean defaultPort =
        port == -1
            || ("https".equals(scheme) && port == 443)
            || ("http".equals(scheme) && port == 80);
    return scheme + "://" + host + (defaultPort ? "" : ":" + port);
  }

  /**
   * {@code input} with an internationalized host in its ASCII form, which {@link URI} requires and
   * a browser's {@code URL.origin} reports; any other input unchanged.
   */
  private static String withAsciiHost(String input) {
    int start = input.indexOf("://");
    if (start < 0 || input.chars().allMatch(c -> c < 0x80)) {
      return input;
    }
    int hostStart = start + 3;
    int end = hostStart;
    while (end < input.length() && "/?#".indexOf(input.charAt(end)) < 0) {
      end++;
    }
    String authority = input.substring(hostStart, end);
    if (authority.contains("@") || authority.startsWith("[")) {
      return input;
    }
    int colon = authority.lastIndexOf(':');
    String host = colon < 0 ? authority : authority.substring(0, colon);
    String port = colon < 0 ? "" : authority.substring(colon);
    return input.substring(0, hostStart)
        + IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
        + port
        + input.substring(end);
  }

  private static ValidationException invalid(String input, String reason) {
    return new ValidationException("Ungültiges Linkziel „" + input + "“: " + reason);
  }
}
