package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.permission.PackageDependencyScanner.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The dependency direction ADR-0036, Entscheidung 12 establishes, held by a test rather than by
 * discipline. Two rules:
 *
 * <ol>
 *   <li><b>{@code io.opaa.permission} depends on no business package.</b> What it needs from one it
 *       declares as a port ({@link GroupMembershipSource}, {@link GroupSubjectDirectory}, {@link
 *       AssetOwnershipDirectory}) and lets that package implement. A dependency the other way would
 *       put the rights derivation back into the package it was taken out of.
 *   <li><b>{@code io.opaa.auth.oidc} depends on no business package either.</b> What the provider
 *       administration needs from {@code io.opaa.group} - what a provider's groups still do, and
 *       their deletion - it declares as {@code ProviderGroupDirectory} and lets that package
 *       implement (#1812). The counter-direction is the one that exists: a group names the provider
 *       it originates from.
 *   <li><b>The business packages do not depend on each other</b>, with one declared exception:
 *       {@code io.opaa.space} reaches {@code io.opaa.library} because a space association names a
 *       library, and nothing in {@code io.opaa.library} names a space. Every other pair is
 *       forbidden in both directions - {@code library} &harr; {@code group} was a real cycle (12
 *       class edges one way, 4 the other) until this package took the permission model out of both.
 * </ol>
 *
 * <p>The scan is source-based; see {@link PackageDependencyScanner} for what that catches that a
 * signature-level reflection check does not, and for the one shape it cannot see.
 */
class PermissionPackageBoundaryTest {

  private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

  private static final String PERMISSION = "io.opaa.permission";
  private static final String LIBRARY = "io.opaa.library";
  private static final String GROUP = "io.opaa.group";
  private static final String SPACE = "io.opaa.space";

  /**
   * Deliberately the subpackage, not {@code io.opaa.auth}: {@code
   * io.opaa.auth.local.LocalHandoverAccountService} holds a {@code GroupMembershipRepository} on
   * purpose (#1563), so the ban applies to the provider administration alone.
   */
  private static final String OIDC = "io.opaa.auth.oidc";

  /** Ordered pairs that must not exist, each with the reason a reviewer needs. */
  private static final Map<List<String>, String> FORBIDDEN_EDGES =
      Map.of(
          List.of(PERMISSION, LIBRARY),
              "the permission model must not know an asset type - a library reaches it through"
                  + " AssetType plus id",
          List.of(PERMISSION, GROUP),
              "the permission model asks for memberships and group subjects through its own ports",
          List.of(PERMISSION, SPACE),
              "a space is the third consumer of the permission model, not part of it",
          List.of(LIBRARY, GROUP), "one half of the cycle ADR-0036, Entscheidung 12 resolved",
          List.of(GROUP, LIBRARY), "the other half of that cycle",
          List.of(GROUP, SPACE), "a group must not learn where its members are organized",
          List.of(SPACE, GROUP),
              "a space reaches groups through the permission model, like every other consumer",
          List.of(LIBRARY, SPACE),
              "space -> library is the one allowed direction between business packages; the"
                  + " counter-direction would make it a cycle",
          List.of(OIDC, GROUP),
              "the provider administration reaches groups through ProviderGroupDirectory, which"
                  + " io.opaa.group implements - the counter-direction (group -> auth.oidc, for"
                  + " the provider a group originates from) is the one that exists");

  private static List<Reference> references;

  @BeforeAll
  static void scanOnce() {
    assertThat(Files.isDirectory(MAIN_SOURCES))
        .as("the test must run with the backend project directory as working directory")
        .isTrue();
    references = PackageDependencyScanner.scan(MAIN_SOURCES);
    assertThat(references).as("the scan must actually find references").isNotEmpty();
  }

  @Test
  void noBusinessPackageIsReachedFromThePermissionModelAndNoPairOfThemFormsACycle() {
    List<String> offenses =
        references.stream()
            .filter(reference -> reasonFor(reference) != null)
            .map(reference -> reference + ": " + reasonFor(reference))
            .distinct()
            .collect(Collectors.toList());

    assertThat(offenses)
        .as(
            "forbidden package dependency - see PermissionPackageBoundaryTest's Javadoc and"
                + " ADR-0036, Entscheidung 12")
        .isEmpty();
  }

  /** Guards the test's own premise: it would pass just as well against an empty package. */
  @Test
  void thePermissionPackageIsActuallyScanned() {
    assertThat(references)
        .as("io.opaa.permission must be part of the scanned tree")
        .anyMatch(reference -> reference.fromPackage().equals(PERMISSION));
  }

  private static String reasonFor(Reference reference) {
    for (Map.Entry<List<String>, String> forbidden : FORBIDDEN_EDGES.entrySet()) {
      String from = forbidden.getKey().get(0);
      String to = forbidden.getKey().get(1);
      if (isWithin(reference.fromPackage(), from) && isWithin(reference.toPackage(), to)) {
        return forbidden.getValue();
      }
    }
    return null;
  }

  private static boolean isWithin(String packageName, String root) {
    return packageName.equals(root) || packageName.startsWith(root + ".");
  }
}
