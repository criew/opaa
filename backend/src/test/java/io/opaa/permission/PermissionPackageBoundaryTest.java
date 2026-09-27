package io.opaa.permission;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.opaa.architecture.MainClasses;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The dependency direction ADR-0036, Entscheidung 12 establishes between the business packages, as
 * far as the layering in {@code io.opaa.architecture.ModularArchitectureTest} does not already
 * forbid it. That test keeps the permission model, the asset shell and {@code io.opaa.auth} below
 * every business package; this one forbids the downward edges the layering would allow:
 *
 * <ol>
 *   <li><b>The business packages do not depend on each other</b>, with one declared exception:
 *       {@code io.opaa.space} reaches the library holdings in {@code io.opaa.knowledge}. A group is
 *       reached through the permission model's ports, never directly - {@code library} &harr;
 *       {@code group} was a real cycle until the permission model was taken out of both.
 *   <li><b>A library reaches the lifecycle only through the ports of {@code io.opaa.permission}</b>
 *       ({@code SuccessionFindingSource}, the reach guard); {@code io.opaa.succession} composes
 *       library, space and group and sits above them.
 *   <li><b>A second asset type stands beside the first, never on it.</b> {@code io.opaa.prompt}
 *       builds on the asset shell and knows neither the library nor a space, a group or the
 *       lifecycle.
 * </ol>
 *
 * <p>The library asset type spans its holdings in {@code io.opaa.knowledge} and its administration
 * in {@code io.opaa.library}; a rule naming the library names both, except that a space may reach
 * the holdings.
 */
class PermissionPackageBoundaryTest {

  private static final String LIBRARY = "io.opaa.library..";
  private static final String KNOWLEDGE = "io.opaa.knowledge..";
  private static final String GROUP = "io.opaa.group..";
  private static final String SPACE = "io.opaa.space..";
  private static final String SUCCESSION = "io.opaa.succession..";
  private static final String ASSET = "io.opaa.asset..";
  private static final String PROMPT = "io.opaa.prompt..";

  private static final List<ArchRule> FORBIDDEN_EDGES =
      List.of(
          forbid(
              LIBRARY,
              GROUP,
              "a library reaches groups through the permission model's ports - the counter-direction"
                  + " group -> library would close the cycle ADR-0036, Entscheidung 12 resolved"),
          forbid(KNOWLEDGE, GROUP, "the same for the library holdings"),
          forbid(
              SPACE,
              GROUP,
              "a space reaches groups through the permission model, like every other consumer"),
          forbid(
              LIBRARY,
              SPACE,
              "space -> library holdings is the one allowed direction between business packages"),
          forbid(
              LIBRARY,
              SUCCESSION,
              "a library contributes a SuccessionFindingSource and asks the guard, both declared in"
                  + " io.opaa.permission - the lifecycle composes the library and sits above it"),
          forbid(
              PROMPT,
              LIBRARY,
              "two asset types stand side by side on the shell; what both need belongs to"
                  + " io.opaa.asset"),
          forbid(PROMPT, KNOWLEDGE, "the same for the library holdings"),
          forbid(
              PROMPT, SPACE, "a prompt library is associated through the shell and knows no space"),
          forbid(
              PROMPT,
              GROUP,
              "a prompt library reaches groups through the shell and the permission model"),
          forbid(
              PROMPT,
              SUCCESSION,
              "the shell's succession source covers every type; the guard is asked by the shell"));

  private static JavaClasses mainClasses;

  @BeforeAll
  static void importMainClasses() {
    mainClasses = MainClasses.get();
  }

  @Test
  void noBusinessPackageReachesAnotherPastThePermissionModel() {
    List<String> reports =
        FORBIDDEN_EDGES.stream()
            .map(rule -> rule.evaluate(mainClasses))
            .filter(EvaluationResult::hasViolation)
            .map(result -> result.getFailureReport().toString())
            .toList();

    assertThat(reports)
        .as("forbidden package dependency - see ADR-0036, Entscheidung 12")
        .isEmpty();
  }

  /** The second asset type stands on the shell - the edge the rules above leave it. */
  @Test
  void thePromptLibraryIsBuiltOnTheAssetShell() {
    assertThat(
            noClasses()
                .that()
                .resideInAPackage(PROMPT)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ASSET)
                .evaluate(mainClasses)
                .hasViolation())
        .as("io.opaa.prompt must reach io.opaa.asset")
        .isTrue();
  }

  private static ArchRule forbid(String from, String to, String reason) {
    return noClasses()
        .that()
        .resideInAPackage(from)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(to)
        .because(reason);
  }
}
