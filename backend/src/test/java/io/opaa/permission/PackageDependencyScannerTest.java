package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.permission.PackageDependencyScanner.Reference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The self-test of {@link PermissionPackageBoundaryTest}'s scanner: a guard that would report
 * nothing no matter what the tree contains is not a guard, and that failure mode is invisible from
 * a green run. Each case writes a tiny source tree and asserts what the scanner does and does not
 * report.
 */
class PackageDependencyScannerTest {

  @Test
  void anInjectedBackDependencyIsReportedWithItsFileAndLine(@TempDir Path root) throws IOException {
    write(
        root,
        "io/opaa/permission/Formula.java",
        """
        package io.opaa.permission;

        import io.opaa.group.GroupRepository;

        class Formula {
          GroupRepository repository;
        }
        """);

    List<Reference> references = PackageDependencyScanner.scan(root);

    assertThat(references)
        .extracting(Reference::fromPackage, Reference::toPackage, Reference::line)
        .contains(tuple("io.opaa.permission", "io.opaa.group", 3));
  }

  /** The shape a signature-level reflection check misses: a call inside a method body. */
  @Test
  void aFullyQualifiedReferenceInAMethodBodyIsReported(@TempDir Path root) throws IOException {
    write(
        root,
        "io/opaa/group/Service.java",
        """
        package io.opaa.group;

        class Service {
          void run() {
            var ids = io.opaa.library.KnowledgeLibraryRepository.class.getName();
          }
        }
        """);

    assertThat(PackageDependencyScanner.scan(root))
        .extracting(Reference::fromPackage, Reference::toPackage)
        .containsExactly(tuple("io.opaa.group", "io.opaa.library"));
  }

  @Test
  void aPackageNamedOnlyInACommentOrAStringIsNotADependency(@TempDir Path root) throws IOException {
    write(
        root,
        "io/opaa/permission/Ports.java",
        """
        package io.opaa.permission;

        /** Implemented by io.opaa.group.GroupMembershipRepository - see io.opaa.library.Access. */
        interface Ports {
          // io.opaa.space.SpaceService does not belong here either
          String NAME = "io.opaa.group.Group";
        }
        """);

    assertThat(PackageDependencyScanner.scan(root)).isEmpty();
  }

  @Test
  void aSubPackageIsReportedAsItsOwnPackage(@TempDir Path root) throws IOException {
    write(
        root,
        "io/opaa/permission/Uses.java",
        """
        package io.opaa.permission;

        import io.opaa.group.sync.DirectorySyncRunLock;

        class Uses {
          DirectorySyncRunLock lock;
        }
        """);

    assertThat(PackageDependencyScanner.scan(root))
        .extracting(Reference::toPackage)
        .containsExactly("io.opaa.group.sync");
  }

  @Test
  void aTreeWithoutCrossPackageReferencesReportsNothing(@TempDir Path root) throws IOException {
    write(
        root,
        "io/opaa/permission/Alone.java",
        """
        package io.opaa.permission;

        import java.util.UUID;

        class Alone {
          UUID id;
        }
        """);

    assertThat(PackageDependencyScanner.scan(root)).isEmpty();
  }

  private static org.assertj.core.groups.Tuple tuple(Object... values) {
    return org.assertj.core.groups.Tuple.tuple(values);
  }

  private static void write(Path root, String relativePath, String content) throws IOException {
    Path file = root.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}
