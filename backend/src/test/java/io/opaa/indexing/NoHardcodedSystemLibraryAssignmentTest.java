package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * #419 acceptance criteria, stated as a test rather than a code-review-only claim: no production
 * code assigns the system library ({@code KnowledgeLibrary.SYSTEM_LIBRARY_ID}) as an indexing
 * target any more. {@code FileProcessingService} used to hardcode it at two call sites - the defect
 * this issue closes.
 *
 * <p>Scans every {@code .java} file under {@code src/main/java} for the constant's simple name
 * {@code SYSTEM_LIBRARY_ID} at all, not only for the exact {@code
 * setLibraryId(KnowledgeLibrary.SYSTEM_LIBRARY_ID)} call-site pattern the original defect used (PR
 * #431 review, nit 4): the narrower pattern would miss a static import, a local variable holding
 * the constant before assignment, or a constructor argument. Any occurrence outside {@link
 * #ALLOWLISTED_FILES} - files with a legitimate, non-assigning reason to name the constant - fails
 * the test, so a new one added later is a deliberate decision, not a silent pass.
 *
 * <p>Deliberately fails rather than skips if {@code src/main/java} cannot be found relative to the
 * working directory: a misconfigured working directory must not look like "no offenders found".
 */
class NoHardcodedSystemLibraryAssignmentTest {

  private static final String CONSTANT_NAME = "SYSTEM_LIBRARY_ID";

  /**
   * Files allowed to mention {@code SYSTEM_LIBRARY_ID} because they declare it or reference it in
   * prose/Javadoc, never to assign it as an indexing target.
   */
  private static final Set<String> ALLOWLISTED_FILES =
      Set.of(
          // Declares the constant itself.
          Path.of("io", "opaa", "library", "KnowledgeLibrary.java").toString(),
          // Javadoc-only mentions: explain why library_id is nullable / describe the migrated
          // interim state, never assign the constant.
          Path.of("io", "opaa", "indexing", "Document.java").toString(),
          Path.of("io", "opaa", "library", "KnowledgeLibraryService.java").toString(),
          Path.of("io", "opaa", "library", "LibraryOwnerType.java").toString(),
          // Javadoc on DocumentIndexingService#requireEditableLibrary explains the one deliberate
          // carve-out (a system admin may target the system library without a grant) - it reads
          // library.isSystemLibrary(), never the constant itself, so there is nothing to assign.
          Path.of("io", "opaa", "indexing", "DocumentIndexingService.java").toString());

  @Test
  void noMainSourceFileAssignsTheSystemLibraryAsAnIndexingTarget() throws IOException {
    Path srcMain = Path.of("src", "main", "java");
    assertThat(Files.isDirectory(srcMain))
        .as(
            "src/main/java not found relative to the working directory (%s) - this test must run"
                + " from the backend module root, e.g. via ./gradlew test",
            Path.of("").toAbsolutePath())
        .isTrue();

    java.util.List<Path> offenders;
    try (Stream<Path> files = Files.walk(srcMain)) {
      offenders =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> !isAllowlisted(srcMain, p))
              .filter(NoHardcodedSystemLibraryAssignmentTest::mentionsTheConstant)
              .toList();
    }

    assertThat(offenders)
        .as(
            "No production code outside %s may reference KnowledgeLibrary.SYSTEM_LIBRARY_ID"
                + " (#419) - every indexing run must target a caller-chosen library",
            ALLOWLISTED_FILES)
        .isEmpty();
  }

  private static boolean isAllowlisted(Path srcMain, Path file) {
    Path relative = srcMain.relativize(file);
    return ALLOWLISTED_FILES.contains(relative.toString());
  }

  private static boolean mentionsTheConstant(Path file) {
    try {
      String content = Files.readString(file, StandardCharsets.UTF_8);
      return content.contains(CONSTANT_NAME);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
