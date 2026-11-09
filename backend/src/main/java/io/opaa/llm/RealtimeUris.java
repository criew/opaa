package io.opaa.llm;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Helpers the live transcription adapters share: the address and reading their messages. */
final class RealtimeUris {

  /** What an upstream error code may contain to be named in a diagnostic. */
  private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

  private RealtimeUris() {}

  /** The stored address as a URI; the message never repeats it. */
  static URI parse(String url) {
    try {
      return new URI(url.strip());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("live address is not a valid URI");
    }
  }

  /** {@code url} with {@code name=value} in the query, unless the query names it already. */
  static URI withQueryParameter(String url, String name, String value) {
    URI uri = parse(url);
    String query = uri.getRawQuery();
    if (query != null) {
      for (String pair : query.split("&")) {
        if (pair.equals(name) || pair.startsWith(name + "=")) {
          return uri;
        }
      }
    }
    String parameter =
        URLEncoder.encode(name, StandardCharsets.UTF_8)
            + "="
            + URLEncoder.encode(value, StandardCharsets.UTF_8);
    String text = url.strip();
    int fragment = text.indexOf('#');
    String head = fragment < 0 ? text : text.substring(0, fragment);
    String tail = fragment < 0 ? "" : text.substring(fragment);
    String separator = query == null ? "?" : head.endsWith("&") || head.endsWith("?") ? "" : "&";
    return parse(head + separator + parameter + tail);
  }

  /** The text of {@code field}, empty when it is missing or no string. */
  static String text(JsonNode message, String field) {
    JsonNode value = message == null ? null : message.get(field);
    return value != null && (value.isString() || value.isNumber()) ? value.asString() : "";
  }

  /**
   * The reason of an {@code error} event: only its code or type, which are technical names; the
   * message is left out, since it may quote what was sent.
   */
  static String errorReason(JsonNode message) {
    JsonNode error = message.get("error");
    String code = "";
    if (error != null && error.isObject()) {
      code = text(error, "code");
      if (code.isEmpty()) {
        code = text(error, "type");
      }
    }
    if (code.isEmpty()) {
      code = text(message, "code");
    }
    return CODE.matcher(code).matches()
        ? "upstream error " + code
        : "the upstream reported an error";
  }
}
