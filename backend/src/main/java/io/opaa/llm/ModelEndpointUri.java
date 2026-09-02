package io.opaa.llm;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Appends an API path to an operator-entered base URL. A trailing slash on the base URL is
 * tolerated and never doubled, and any query string (e.g. an Azure {@code api-version} parameter)
 * is preserved and moved behind the appended path instead of ending up in the middle of it.
 */
final class ModelEndpointUri {

  private ModelEndpointUri() {}

  /**
   * @param path the path to append, starting with a slash.
   * @throws URISyntaxException when the result is not a valid URI.
   */
  static URI append(String baseUrl, String path) throws URISyntaxException {
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
}
