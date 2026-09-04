package io.opaa.llm;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * The shared handling of an operator-entered base address, for every model role alike (#1147).
 *
 * <p>Appends an API path: a trailing slash on the base URL is tolerated and never doubled, and any
 * query string (e.g. an Azure {@code api-version} parameter) is preserved and moved behind the
 * appended path instead of ending up in the middle of it.
 *
 * <p><b>An address carrying userinfo is rejected, never stripped</b> ({@code
 * https://user:secret@host/v1}). Stripping would accept a configuration whose stated intent - "use
 * these credentials" - is then silently not carried out; rejecting says so. The rejection text
 * deliberately does not echo the address, because it travels into exactly the API responses, log
 * lines and status displays this rule exists to keep credentials out of.
 */
final class ModelEndpointUri {

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
  static boolean containsCredentials(String baseUrl) {
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
