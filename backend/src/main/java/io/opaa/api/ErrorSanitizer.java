package io.opaa.api;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stateless utility that strips sensitive data (API keys, file paths, URL params) from strings. */
public class ErrorSanitizer {

  private static final Pattern API_KEY_PATTERN =
      Pattern.compile(
          "\\b(sk-[a-zA-Z0-9]{20,}|api[_-]?key[\"'=:\\s]+[a-zA-Z0-9-]+)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern URL_WITH_PARAMS_PATTERN =
      Pattern.compile("(https?://[^\\s?]+)\\?[^\\s]+");

  private static final Pattern URL_OR_FILE_PATH_PATTERN =
      Pattern.compile("https?://[^\\s]+|(/[a-zA-Z0-9._-]+){2,}(/[^\\s]*)?");

  private static final Pattern WINDOWS_PATH_PATTERN =
      Pattern.compile("[A-Z]:\\\\([^\\s\\\\]+\\\\){1,}[^\\s]*", Pattern.CASE_INSENSITIVE);

  public String sanitize(String message) {
    if (message == null) {
      return null;
    }

    String result = message;
    result = API_KEY_PATTERN.matcher(result).replaceAll("[REDACTED]");
    result = URL_WITH_PARAMS_PATTERN.matcher(result).replaceAll("$1?[REDACTED]");
    result = replaceFilePathsPreservingUrls(result);
    result = WINDOWS_PATH_PATTERN.matcher(result).replaceAll("[PATH]");
    return result;
  }

  private String replaceFilePathsPreservingUrls(String input) {
    Matcher matcher = URL_OR_FILE_PATH_PATTERN.matcher(input);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      if (matcher.group().startsWith("http")) {
        matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
      } else {
        matcher.appendReplacement(sb, "[PATH]");
      }
    }
    matcher.appendTail(sb);
    return sb.toString();
  }
}
