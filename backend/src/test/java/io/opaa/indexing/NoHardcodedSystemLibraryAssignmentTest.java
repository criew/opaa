package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * #419 acceptance criteria, stated as a test rather than a code-review-only claim: no production
 * code assigns the system library ({@code KnowledgeLibrary.SYSTEM_LIBRARY_ID}) as an indexing
 * target any more. {@code FileProcessingService} used to hardcode it at two call sites - the defect
 * this issue closes - so this scans every {@code .java} file under {@code src/main/java} for that
 * specific assignment pattern, not merely for the constant's name: {@code KnowledgeLibrary.java}
 * itself still declares it (existing documents in the system library are intentionally left in
 * place, see the class's Javadoc), and other files still mention it in prose.
 */
class NoHardcodedSystemLibraryAssignmentTest {

  private static final String FORBIDDEN_PATTERN = "setLibraryId(KnowledgeLibrary.SYSTEM_LIBRARY_ID";

  @Test
  void noMainSourceFileAssignsTheSystemLibraryAsAnIndexingTarget() throws IOException {
    Path srcMain = Path.of("src", "main", "java");
    assumeTrue(
        Files.isDirectory(srcMain),
        "src/main/java not found relative to the working directory - skipping rather than"
            + " failing for the wrong reason");

    List<Path> offenders;
    try (Stream<Path> files = Files.walk(srcMain)) {
      offenders =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(NoHardcodedSystemLibraryAssignmentTest::containsForbiddenAssignment)
              .toList();
    }

    assertThat(offenders)
        .as(
            "No production code may assign KnowledgeLibrary.SYSTEM_LIBRARY_ID as an indexing"
                + " target (#419) - every indexing run must target a caller-chosen library")
        .isEmpty();
  }

  private static boolean containsForbiddenAssignment(Path file) {
    try {
      String content = Files.readString(file, StandardCharsets.UTF_8);
      return content.contains(FORBIDDEN_PATTERN);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
