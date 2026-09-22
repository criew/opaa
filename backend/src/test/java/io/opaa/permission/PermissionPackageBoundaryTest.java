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
 *   <li><b>Nothing below depends on {@code io.opaa.succession}.</b> The lifecycle composes library,
 *       space and group (#1819) and therefore sits above them, like {@code io.opaa.revision}; what
 *       the three of them need from it - the frozen reach and the closing of a record - they reach
 *       through ports of {@code io.opaa.permission}.
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
  private static final String AUDIT = "io.opaa.audit";
  private static final String SUCCESSION = "io.opaa.succession";

  /**
   * Deliberately the subpackage, not {@code io.opaa.auth}: {@code
   * io.opaa.auth.local.LocalHandoverAccountService} holds a {@code GroupMembershipRepository} on
   * purpose (#1563), so the ban applies to the provider administration alone.
   */
  private static final String OIDC = "io.opaa.auth.oidc";

  /** Ordered pairs that must not exist, each with the reason a reviewer needs. */
  private static final Map<List<String>, String> FORBIDDEN_EDGES =
      Map.ofEntries(
          Map.entry(
              List.of(PERMISSION, LIBRARY),
              "the permission model must not know an asset type - a library reaches it through"
                  + " AssetType plus id"),
          Map.entry(
              List.of(PERMISSION, GROUP),
              "the permission model asks for memberships and group subjects through its own ports"),
          Map.entry(
              List.of(PERMISSION, SPACE),
              "a space is the third consumer of the permission model, not part of it"),
          Map.entry(
              List.of(LIBRARY, GROUP), "one half of the cycle ADR-0036, Entscheidung 12 resolved"),
          Map.entry(List.of(GROUP, LIBRARY), "the other half of that cycle"),
          Map.entry(
              List.of(GROUP, SPACE), "a group must not learn where its members are organized"),
          Map.entry(
              List.of(SPACE, GROUP),
              "a space reaches groups through the permission model, like every other consumer"),
          Map.entry(
              List.of(LIBRARY, SPACE),
              "space -> library is the one allowed direction between business packages; the"
                  + " counter-direction would make it a cycle"),
          Map.entry(
              List.of(OIDC, GROUP),
              "the provider administration reaches groups through ProviderGroupDirectory, which"
                  + " io.opaa.group implements - the counter-direction (group -> auth.oidc, for"
                  + " the provider a group originates from) is the one that exists"),
          Map.entry(
              List.of(AUDIT, PERMISSION),
              "every fachpaket writes its events through io.opaa.audit, so a dependency out of it"
                  + " is a cycle - a reading path that composes several of them lives in"
                  + " io.opaa.revision, above all of them (#1822)"),
          Map.entry(
              List.of(PERMISSION, SUCCESSION),
              "the lifecycle composes the three fachpakete and therefore sits above them (#1819);"
                  + " what the permission model needs from it - the reach guard and the closing of"
                  + " a record - it declares as SuccessionReachGuard and SuccessionCaseCloser"),
          Map.entry(
              List.of(LIBRARY, SUCCESSION),
              "a library contributes a SuccessionFindingSource and asks the guard, both declared"
                  + " in io.opaa.permission - the counter-direction would be a cycle"),
          Map.entry(List.of(GROUP, SUCCESSION), "same direction, same reason"),
          Map.entry(List.of(SPACE, SUCCESSION), "same direction, same reason"),
          Map.entry(List.of(AUDIT, LIBRARY), "same direction, same reason"),
          Map.entry(List.of(AUDIT, SPACE), "same direction, same reason"));

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
