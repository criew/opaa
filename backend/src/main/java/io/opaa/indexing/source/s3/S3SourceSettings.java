package io.opaa.indexing.source.s3;

import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;

/**
 * The typed configuration of an S3 library (ADR-0027, Entscheidung 1) as {@code
 * knowledge_libraries.source_settings} carries it: the signing region, the addressing style, one to
 * fifty non-overlapping {@link S3Scope}s and optional glob patterns on the object key. Validated on
 * construction; the endpoint, the credentials, the proxy and the TLS switch live in the library's
 * existing columns and are never part of this record.
 *
 * @param region the region every request is signed for; {@code null} means {@link
 *     S3Connection#DEFAULT_REGION}
 * @param pathStyle {@code true} addresses {@code endpoint/bucket/key}, {@code false} {@code
 *     bucket.endpoint/key}
 * @param includePatterns when not empty, only keys matching at least one are indexed
 * @param excludePatterns keys matching any of them are skipped
 */
public record S3SourceSettings(
    String region,
    boolean pathStyle,
    List<S3Scope> scopes,
    List<String> includePatterns,
    List<String> excludePatterns) {

  public static final int MAX_REGION_LENGTH = 64;
  public static final int MAX_PATTERNS = 50;
  public static final int MAX_PATTERN_LENGTH = 255;

  public S3SourceSettings {
    region = normalizeRegion(region);
    scopes = scopes == null ? List.of() : List.copyOf(scopes);
    try {
      S3Scope.requireValidSelection(scopes);
    } catch (S3Scope.InvalidS3ScopeException e) {
      throw new InvalidS3SourceSettingsException(e.getMessage());
    }
    includePatterns = normalizePatterns(includePatterns, "Einschlussmuster");
    excludePatterns = normalizePatterns(excludePatterns, "Ausschlussmuster");
  }

  /** The region a connection signs for - the default when none was configured. */
  public String effectiveRegion() {
    return region == null ? S3Connection.DEFAULT_REGION : region;
  }

  private static String normalizeRegion(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String region = raw.strip();
    if (region.length() > MAX_REGION_LENGTH) {
      throw new InvalidS3SourceSettingsException(
          "Die Region darf höchstens " + MAX_REGION_LENGTH + " Zeichen lang sein.");
    }
    for (char c : region.toCharArray()) {
      if (!(Character.isLetterOrDigit(c) || c == '-')) {
        throw new InvalidS3SourceSettingsException(
            "Die Region „" + region + "“ ist ungültig: nur Buchstaben, Ziffern und Bindestriche.");
      }
    }
    return region;
  }

  private static List<String> normalizePatterns(List<String> raw, String label) {
    if (raw == null) {
      return List.of();
    }
    if (raw.size() > MAX_PATTERNS) {
      throw new InvalidS3SourceSettingsException(
          "Höchstens " + MAX_PATTERNS + " " + label + " je Bibliothek sind zulässig.");
    }
    List<String> patterns = new ArrayList<>();
    for (String candidate : raw) {
      String pattern = candidate == null ? "" : candidate.strip();
      if (pattern.isEmpty()) {
        throw new InvalidS3SourceSettingsException(label + ": ein leeres Muster ist unzulässig.");
      }
      if (pattern.length() > MAX_PATTERN_LENGTH) {
        throw new InvalidS3SourceSettingsException(
            label + ": ein Muster darf höchstens " + MAX_PATTERN_LENGTH + " Zeichen lang sein.");
      }
      try {
        FileSystems.getDefault().getPathMatcher("glob:" + pattern);
      } catch (IllegalArgumentException e) {
        throw new InvalidS3SourceSettingsException(
            label + ": „" + pattern + "“ ist kein gültiges Glob-Muster.");
      }
      if (patterns.contains(pattern)) {
        throw new InvalidS3SourceSettingsException(
            label + ": das Muster „" + pattern + "“ ist mehrfach angegeben.");
      }
      patterns.add(pattern);
    }
    return List.copyOf(patterns);
  }

  /** Thrown for a value that cannot be a library's S3 configuration; the message is user-facing. */
  public static final class InvalidS3SourceSettingsException extends IllegalArgumentException {
    public InvalidS3SourceSettingsException(String message) {
      super(message);
    }
  }
}
