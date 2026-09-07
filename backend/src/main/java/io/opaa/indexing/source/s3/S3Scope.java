package io.opaa.indexing.source.s3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One scope of an S3 library (ADR-0027, Entscheidung 2): a bucket and an optional key prefix.
 * Normalised on construction - the bucket follows the AWS naming rules MinIO and Ceph adopt, the
 * prefix carries no leading slash and, when not empty, ends with one, so {@code 2025/protokolle}
 * means the "folder" and never {@code 2025/protokolle-alt/...}. An empty prefix is the whole
 * bucket.
 *
 * <p>Two scopes of one library must not overlap ({@link #requireValidSelection}): the same key
 * under two scopes would collide on {@code uk_documents_library_path}.
 */
public record S3Scope(String bucket, String prefix) {

  /** Upper bound of scopes per library - a guard against misconfiguration, not against load. */
  public static final int MAX_PER_LIBRARY = 50;

  static final int MAX_PREFIX_LENGTH = 1024;

  private static final Pattern BUCKET_NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

  public S3Scope {
    bucket = validateBucket(bucket);
    prefix = normalizePrefix(prefix);
  }

  public static S3Scope of(String bucket, String prefix) {
    return new S3Scope(bucket, prefix);
  }

  /** {@code bucket/prefix}, or the bare bucket for the whole-bucket scope - the scope's key. */
  public String key() {
    return prefix.isEmpty() ? bucket : bucket + "/" + prefix;
  }

  /** Whether {@code objectKey} lies inside this scope's prefix. */
  public boolean contains(String objectKey) {
    return objectKey != null && objectKey.startsWith(prefix);
  }

  /** Whether the two scopes share at least one possible key. */
  public boolean overlaps(S3Scope other) {
    return bucket.equals(other.bucket)
        && (prefix.startsWith(other.prefix) || other.prefix.startsWith(prefix));
  }

  /**
   * The rules a library's scope selection must meet: at least one scope, at most {@link
   * #MAX_PER_LIBRARY}, no two overlapping.
   *
   * @throws InvalidS3ScopeException with a German, user-facing message
   */
  public static void requireValidSelection(Collection<S3Scope> scopes) {
    if (scopes == null || scopes.isEmpty()) {
      throw new InvalidS3ScopeException(
          "Mindestens ein Geltungsbereich (Bucket) ist erforderlich.");
    }
    if (scopes.size() > MAX_PER_LIBRARY) {
      throw new InvalidS3ScopeException(
          "Höchstens " + MAX_PER_LIBRARY + " Geltungsbereiche je Bibliothek sind zulässig.");
    }
    requireDisjoint(scopes);
  }

  /**
   * @throws InvalidS3ScopeException naming both scopes when two of them overlap
   */
  public static void requireDisjoint(Collection<S3Scope> scopes) {
    List<S3Scope> seen = new ArrayList<>();
    for (S3Scope scope : scopes) {
      for (S3Scope other : seen) {
        if (scope.overlaps(other)) {
          throw new InvalidS3ScopeException(
              "Die Geltungsbereiche „"
                  + other.key()
                  + "“ und „"
                  + scope.key()
                  + "“ überschneiden sich; jedes Objekt darf nur in einem Bereich liegen.");
        }
      }
      seen.add(scope);
    }
  }

  static String validateBucket(String raw) {
    String bucket = raw == null ? "" : raw.strip();
    if (bucket.isEmpty()) {
      throw new InvalidS3ScopeException("Der Bucket-Name ist erforderlich.");
    }
    if (!BUCKET_NAME.matcher(bucket).matches()
        || bucket.contains("..")
        || IPV4.matcher(bucket).matches()) {
      throw new InvalidS3ScopeException(
          "Der Bucket-Name „"
              + bucket
              + "“ ist ungültig: 3 bis 63 Zeichen, nur Kleinbuchstaben, Ziffern, Punkte und"
              + " Bindestriche, beginnend und endend mit Buchstabe oder Ziffer, keine IP-Adresse.");
    }
    return bucket;
  }

  static String normalizePrefix(String raw) {
    String prefix = raw == null ? "" : raw.strip();
    while (prefix.startsWith("/")) {
      prefix = prefix.substring(1);
    }
    if (prefix.isEmpty()) {
      return "";
    }
    if (!prefix.endsWith("/")) {
      prefix = prefix + "/";
    }
    if (prefix.length() > MAX_PREFIX_LENGTH) {
      throw new InvalidS3ScopeException(
          "Das Präfix darf höchstens " + MAX_PREFIX_LENGTH + " Zeichen lang sein.");
    }
    for (char c : prefix.toCharArray()) {
      if (c <= ' ' || c == 0x7f) {
        throw new InvalidS3ScopeException(
            "Das Präfix „" + prefix + "“ enthält Leer- oder Steuerzeichen und ist kein Schlüssel.");
      }
    }
    // the scope key `bucket/prefix` is stored comma-separated in a run's listing assessment
    if (prefix.indexOf(',') >= 0) {
      throw new InvalidS3ScopeException(
          "Das Präfix „"
              + prefix
              + "“ enthält ein Komma; Kommas sind in Geltungsbereichen nicht"
              + " zulässig.");
    }
    return prefix;
  }

  /** Thrown for a bucket, prefix or selection that cannot be a scope. */
  public static final class InvalidS3ScopeException extends IllegalArgumentException {
    public InvalidS3ScopeException(String message) {
      super(message);
    }
  }
}
