package io.opaa.observability;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The tag keys no metric may carry (ADR-0071): each would name a person, a space, a library, a
 * document, a file, an address or what someone searched for, and so turn an installation-wide count
 * into one per object or person. A key matches regardless of case and of {@code .}, {@code _} or
 * {@code -} between its words.
 */
final class ForbiddenMetricTags {

  static final Set<String> KEYS =
      Set.of(
          "user",
          "userid",
          "username",
          "account",
          "accountid",
          "person",
          "subject",
          "email",
          "principal",
          "organization",
          "organizationid",
          "group",
          "groupid",
          "space",
          "spaceid",
          "library",
          "libraryid",
          "folder",
          "folderid",
          "document",
          "documentid",
          "chunk",
          "chunkid",
          "chat",
          "chatid",
          "conversation",
          "conversationid",
          "file",
          "filename",
          "path",
          "filepath",
          "url",
          "query",
          "question",
          "searchterm",
          "prompt",
          "token",
          "tokenid");

  /**
   * Meters whose {@code path} tag is the fixed working directory of the process (Spring Boot's disk
   * space metrics), never a path a person chose.
   */
  private static final Set<String> FIXED_PATH_METERS = Set.of("disk.free", "disk.total");

  /** A value that is an identifier of an object or an e-mail address, whatever the key says. */
  private static final Pattern IDENTIFYING_VALUE =
      Pattern.compile(
          "(?i).*(\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"
              + "|[^\\s@]+@[^\\s@]+\\.[a-z]{2,}).*");

  private ForbiddenMetricTags() {}

  /** Whether {@code value} contains a UUID or an e-mail address. */
  static boolean isIdentifyingValue(String value) {
    return IDENTIFYING_VALUE.matcher(value).matches();
  }

  /** Whether {@code key} on the meter {@code meterName} names one of {@link #KEYS}. */
  static boolean isForbidden(String meterName, String key) {
    boolean fixedPath = FIXED_PATH_METERS.contains(meterName) && "path".equals(key);
    return isForbidden(key) && !fixedPath;
  }

  /** Whether {@code key} names one of {@link #KEYS}. */
  static boolean isForbidden(String key) {
    return KEYS.contains(key.toLowerCase(Locale.ROOT).replaceAll("[._-]", ""));
  }
}
