package io.opaa.instance;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/**
 * The version of an instance and the oldest version it may run next to during a rolling update
 * (ADR-0053, Entscheidung 4). Two versions run side by side when the older one is at least the
 * oldest version the newer one names; outside that window an instance does not start, whether it is
 * the older or the newer one.
 *
 * @param version the semantic version of the build, {@code 0.0.0-dev} outside a release
 * @param oldestCompatible the oldest version this build runs next to; the version itself at a
 *     version boundary without rolling update
 */
record InstanceVersion(String version, String oldestCompatible) {

  /** The file the release names the oldest compatible version in. */
  static final String WINDOW_RESOURCE = "instance/rolling-update.properties";

  static final String WINDOW_PROPERTY = "oldest-compatible-version";

  private static final Pattern SEMVER =
      Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?");

  InstanceVersion {
    requireSemantic(version, "version");
    requireSemantic(oldestCompatible, "oldestCompatible");
    if (compare(oldestCompatible, version) > 0) {
      throw new IllegalArgumentException(
          "oldest compatible version " + oldestCompatible + " is newer than " + version);
    }
  }

  /** {@code version} with the window {@link #WINDOW_RESOURCE} names, the version itself if none. */
  static InstanceVersion of(String version) {
    String declared = declaredWindow();
    return new InstanceVersion(version, declared == null ? version : declared);
  }

  /** Whether this version and {@code other} may run side by side. */
  boolean compatibleWith(InstanceVersion other) {
    int order = compare(version, other.version);
    if (order == 0) {
      return true;
    }
    InstanceVersion newer = order > 0 ? this : other;
    InstanceVersion older = order > 0 ? other : this;
    return compare(older.version, newer.oldestCompatible) >= 0;
  }

  /** Semantic version precedence; a pre-release comes before its release. */
  static int compare(String a, String b) {
    Matcher left = match(a);
    Matcher right = match(b);
    for (int group = 1; group <= 3; group++) {
      int order =
          Long.compare(Long.parseLong(left.group(group)), Long.parseLong(right.group(group)));
      if (order != 0) {
        return order;
      }
    }
    return comparePreRelease(left.group(4), right.group(4));
  }

  private static int comparePreRelease(String a, String b) {
    if (Objects.equals(a, b)) {
      return 0;
    }
    if (a == null) {
      return 1;
    }
    if (b == null) {
      return -1;
    }
    String[] left = a.split("\\.");
    String[] right = b.split("\\.");
    for (int i = 0; i < Math.min(left.length, right.length); i++) {
      int order = compareIdentifier(left[i], right[i]);
      if (order != 0) {
        return order;
      }
    }
    return Integer.compare(left.length, right.length);
  }

  private static int compareIdentifier(String a, String b) {
    boolean numericA = a.chars().allMatch(Character::isDigit);
    boolean numericB = b.chars().allMatch(Character::isDigit);
    if (numericA && numericB) {
      return Long.compare(Long.parseLong(a), Long.parseLong(b));
    }
    if (numericA != numericB) {
      return numericA ? -1 : 1;
    }
    return a.compareTo(b);
  }

  private static Matcher match(String version) {
    Matcher matcher = SEMVER.matcher(version);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("not a semantic version: " + version);
    }
    return matcher;
  }

  private static void requireSemantic(String version, String name) {
    Objects.requireNonNull(version, name);
    match(version);
  }

  private static String declaredWindow() {
    ClassPathResource resource = new ClassPathResource(WINDOW_RESOURCE);
    if (!resource.exists()) {
      return null;
    }
    Properties properties = new Properties();
    try (InputStream in = resource.getInputStream()) {
      properties.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String declared = properties.getProperty(WINDOW_PROPERTY, "").trim();
    return declared.isEmpty() ? null : declared;
  }
}
