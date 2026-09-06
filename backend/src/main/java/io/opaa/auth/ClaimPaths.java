package io.opaa.auth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Reads a claim by a dot-separated path ({@code realm_access.roles}) out of a token's claim map
 * (ADR-0025, Entscheidung 4). A path that leads nowhere, or to a value of another shape than
 * expected, yields nothing rather than an error - a provider's token never fails a request because
 * of its claim layout.
 */
final class ClaimPaths {

  private ClaimPaths() {}

  /** The string at {@code path}, or {@code null} when absent, blank or not a string. */
  static String string(Map<String, Object> claims, String path) {
    Object value = valueAt(claims, path);
    if (value instanceof String text && !text.isBlank()) {
      return text;
    }
    return null;
  }

  /**
   * The strings at {@code path}: every string element of a collection there, or the one string
   * there; blank entries dropped; empty when absent.
   */
  static List<String> strings(Map<String, Object> claims, String path) {
    Object value = valueAt(claims, path);
    List<String> result = new ArrayList<>();
    if (value instanceof Collection<?> values) {
      for (Object element : values) {
        if (element instanceof String text && !text.isBlank()) {
          result.add(text);
        }
      }
    } else if (value instanceof String text && !text.isBlank()) {
      result.add(text);
    }
    return List.copyOf(result);
  }

  private static Object valueAt(Map<String, Object> claims, String path) {
    if (claims == null || path == null || path.isBlank()) {
      return null;
    }
    Object current = claims;
    for (String segment : path.split("\\.")) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(segment);
      if (current == null) {
        return null;
      }
    }
    return current;
  }
}
