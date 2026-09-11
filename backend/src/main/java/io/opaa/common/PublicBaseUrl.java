package io.opaa.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds the links that go into mail (#1536, ADR-0033 Entscheidung 10) from {@code
 * OPAA_PUBLIC_BASE_URL}.
 *
 * <p><b>The {@code Host} header of the request is never used as the base.</b> It is attacker-
 * controlled on any deployment whose reverse proxy passes it through, and a password-reset link
 * built from it is a phishing link OPAA sends itself, signed by its own sender address.
 *
 * <p>{@link #isConfigured()} is a precondition, not a detail: flows that only work with a link -
 * Passwort vergessen, Selbstregistrierung - stay switched off while it is empty (ADR-0033,
 * Entscheidung 10 and 11), rather than sending mail nobody can act on.
 */
@Component
public class PublicBaseUrl {

  private static final Logger log = LoggerFactory.getLogger(PublicBaseUrl.class);

  private final URI base;

  public PublicBaseUrl(PublicBaseUrlProperties properties) {
    this.base = parse(properties.publicBaseUrl());
  }

  /** Whether a usable base address is configured. */
  public boolean isConfigured() {
    return base != null;
  }

  /** The configured base address without a trailing slash, or empty while none is set. */
  public Optional<URI> base() {
    return Optional.ofNullable(base);
  }

  /**
   * {@code {base}/{path}?{queryName}={queryValue}}, or empty while no base is configured. The query
   * value is percent-encoded; the path is taken as given, since callers pass literals.
   */
  public Optional<String> link(String path, String queryName, String queryValue) {
    return link(path).map(url -> url + "?" + queryName + "=" + encode(queryValue));
  }

  /** {@code {base}/{path}}, or empty while no base is configured. */
  public Optional<String> link(String path) {
    if (base == null) {
      return Optional.empty();
    }
    String normalized = path == null || path.isBlank() ? "" : "/" + trimSlashes(path);
    return Optional.of(base + normalized);
  }

  private static URI parse(String configured) {
    if (!StringUtils.hasText(configured)) {
      return null;
    }
    String trimmed = configured.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    try {
      URI parsed = new URI(trimmed);
      if (parsed.getScheme() == null || parsed.getHost() == null) {
        log.warn(
            "OPAA_PUBLIC_BASE_URL ist keine vollstaendige Adresse mit Schema und Host - Links in"
                + " E-Mails bleiben abgeschaltet");
        return null;
      }
      return parsed;
    } catch (URISyntaxException e) {
      log.warn(
          "OPAA_PUBLIC_BASE_URL ist keine gueltige Adresse - Links in E-Mails bleiben abgeschaltet");
      return null;
    }
  }

  private static String trimSlashes(String path) {
    String trimmed = path;
    while (trimmed.startsWith("/")) {
      trimmed = trimmed.substring(1);
    }
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  private static String encode(String value) {
    return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
