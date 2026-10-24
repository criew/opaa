package io.opaa.llm;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * The shared handling of an operator-entered base address, for every model role alike. An address
 * carrying userinfo ({@code https://user:secret@host/v1}) is rejected, never stripped; appending a
 * path tolerates a trailing slash without doubling it and keeps any query string last (see
 * docs/features/llm-integration.md for the reasoning behind both).
 */
public final class ModelEndpointUri {

  /** German, user-facing rejection text; never contains the rejected address itself. */
  static final String CREDENTIALS_REJECTED_MESSAGE =
      "Die Basis-Adresse darf keine Anmeldedaten enthalten (Form"
          + " \"https://benutzer:passwort@host\"). Tragen Sie die Adresse ohne Anmeldedaten ein"
          + " und hinterlegen Sie den Zugangsschlüssel im dafür vorgesehenen Feld.";

  private ModelEndpointUri() {}

  /**
   * Whether {@code baseUrl} carries userinfo. An address {@link URI} refuses to parse is scanned
   * textually rather than passed through as "not a URI": a rejection rule that only covers
   * well-formed addresses is no rejection rule.
   */
  public static boolean containsCredentials(String baseUrl) {
    if (baseUrl == null) {
      return false;
    }
    String trimmed = baseUrl.strip();
    try {
      if (new URI(trimmed).getRawUserInfo() != null) {
        return true;
      }
    } catch (URISyntaxException e) {
      // Falls through to the textual scan, which needs no parsable URI.
    }
    return authorityContainsAtSign(trimmed);
  }

  /**
   * {@code baseUrl} as a log line may name it: scheme, host, port and path, without userinfo, query
   * or fragment, where a key could stand. Works on the text, so an address {@link URI} refuses
   * (such as a host with an underscore) is shortened as well.
   */
  public static String withoutSecrets(String baseUrl) {
    if (baseUrl == null) {
      return null;
    }
    String text = baseUrl.strip();
    int query = text.indexOf('?');
    int fragment = text.indexOf('#');
    int cut = query < 0 ? fragment : fragment < 0 ? query : Math.min(query, fragment);
    if (cut >= 0) {
      text = text.substring(0, cut);
    }
    int schemeEnd = text.indexOf("://");
    int authorityStart = schemeEnd >= 0 ? schemeEnd + 3 : 0;
    int authorityEnd = text.indexOf('/', authorityStart);
    if (authorityEnd < 0) {
      authorityEnd = text.length();
    }
    int at = text.lastIndexOf('@', authorityEnd - 1);
    return at >= authorityStart ? text.substring(0, authorityStart) + text.substring(at + 1) : text;
  }

  /**
   * @param path the path to append, starting with a slash.
   * @throws IllegalArgumentException when the base URL carries credentials.
   * @throws URISyntaxException when the result is not a valid URI.
   */
  static URI append(String baseUrl, String path) throws URISyntaxException {
    if (containsCredentials(baseUrl)) {
      throw new IllegalArgumentException("base URL must not carry credentials");
    }
    String trimmed = baseUrl.strip();
    int queryIndex = trimmed.indexOf('?');
    String withoutQuery = queryIndex >= 0 ? trimmed.substring(0, queryIndex) : trimmed;
    String query = queryIndex >= 0 ? trimmed.substring(queryIndex) : "";
    String withoutTrailingSlash =
        withoutQuery.endsWith("/")
            ? withoutQuery.substring(0, withoutQuery.length() - 1)
            : withoutQuery;
    return new URI(withoutTrailingSlash + path + query);
  }

  /** An {@code @} anywhere in the authority component separates userinfo from the host. */
  private static boolean authorityContainsAtSign(String baseUrl) {
    int schemeEnd = baseUrl.indexOf("://");
    int authorityStart = schemeEnd >= 0 ? schemeEnd + 3 : 0;
    int authorityEnd = baseUrl.length();
    for (int i = authorityStart; i < baseUrl.length(); i++) {
      char c = baseUrl.charAt(i);
      if (c == '/' || c == '?' || c == '#') {
        authorityEnd = i;
        break;
      }
    }
    return baseUrl.lastIndexOf('@', authorityEnd - 1) >= authorityStart;
  }
}
