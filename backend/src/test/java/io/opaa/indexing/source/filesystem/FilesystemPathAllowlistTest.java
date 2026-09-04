package io.opaa.indexing.source.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FilesystemPathAllowlist} (#484, ADR-0018 Entscheidung 6). Deliberately
 * without Spring context - the allowlist is pure path logic, and these tests exist specifically to
 * turn red the moment someone "simplifies" the implementation to a lexical {@code
 * String.startsWith} check, which is vulnerable to the prefix trap in {@link
 * #rejectsSiblingDirectoryWithMatchingPrefix()}.
 */
class FilesystemPathAllowlistTest {

  private static FilesystemPathAllowlist allowlistOf(String... baseDirectories) {
    return new FilesystemPathAllowlist(properties(Arrays.asList(baseDirectories)));
  }

  private static FilesystemProperties properties(List<String> allowlist) {
    return new FilesystemProperties(allowlist);
  }

  @Test
  void rejectsSiblingDirectoryWithMatchingPrefix() {
    // /data-evil is NOT underneath /data, even though the string "/data-evil" starts with the
    // string "/data" - a naive String.startsWith(base) check would wrongly allow this.
    FilesystemPathAllowlist allowlist = allowlistOf("/data");

    assertThat(allowlist.isAllowed("/data-evil/secrets")).isFalse();
  }

  @Test
  void rejectsPathTraversalOutOfTheBaseDirectory() {
    FilesystemPathAllowlist allowlist = allowlistOf("/data");

    assertThat(allowlist.isAllowed("/data/../etc")).isFalse();
  }

  @Test
  void emptyAllowlistEntryMatchesNothing() {
    // A trailing comma in OPAA_INDEXING_FILESYSTEM_ALLOWLIST (e.g. "/data,") yields an empty list
    // entry. It must not silently normalise to a base that matches every path.
    FilesystemPathAllowlist allowlist = allowlistOf("/data", "");

    assertThat(allowlist.isAllowed("/etc/passwd")).isFalse();
  }

  @Test
  void baseDirectoryWithTrailingSlashStillMatches() {
    FilesystemPathAllowlist allowlist = allowlistOf("/data/");

    assertThat(allowlist.isAllowed("/data/documents/report.pdf")).isTrue();
  }

  @Test
  void nullAllowlistIsNotConfigured() {
    FilesystemPathAllowlist allowlist = new FilesystemPathAllowlist(properties(null));

    assertThat(allowlist.isConfigured()).isFalse();
  }

  @Test
  void emptyAllowlistIsNotConfigured() {
    FilesystemPathAllowlist allowlist = allowlistOf();

    assertThat(allowlist.isConfigured()).isFalse();
  }
}
