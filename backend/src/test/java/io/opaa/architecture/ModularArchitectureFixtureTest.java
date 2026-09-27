package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Proves that every rule of {@link ModularArchitecture} fires. Each example tree under {@code
 * io.opaa.architecture.fixture.<scenario>} mirrors the top-level packages of the backend and breaks
 * one rule; the rules are applied with that scenario package as their root.
 */
class ModularArchitectureFixtureTest {

  private static final String FIXTURES = "io.opaa.architecture.fixture";

  @Test
  void aConformingTreePassesEveryRule() {
    Scenario valid = new Scenario("valid");

    assertThat(valid.architecture.all())
        .allSatisfy(rule -> assertThat(rule.evaluate(valid.classes).hasViolation()).isFalse());
  }

  @Test
  void anUpwardEdgeInsideAModuleBreaksTheLayering() {
    Scenario scenario = new Scenario("upwardinmodule");

    assertThat(scenario.violations(ModularArchitecture::noPackageDependsOnAHigherLayer))
        .singleElement(STRING)
        .contains("common -> observability points upward", "common.Low");
    assertThat(scenario.violations(ModularArchitecture::modulesDependOnlyOnAllowedModules))
        .isEmpty();
  }

  /** {@code sourceaccess} sits above {@code auth} in the layering, but its module below it. */
  @Test
  void anUpwardEdgeBetweenModulesBreaksTheModules() {
    Scenario scenario = new Scenario("upwardacrossmodules");

    assertThat(scenario.violations(ModularArchitecture::modulesDependOnlyOnAllowedModules))
        .singleElement(STRING)
        .contains("module FOUNDATION -> IDENTITY is no allowed edge", "sourceaccess.Fetcher");
    assertThat(scenario.violations(ModularArchitecture::noPackageDependsOnAHigherLayer)).isEmpty();
  }

  @Test
  void aDownwardEdgeBetweenModulesThatIsNotAllowedBreaksTheModules() {
    Scenario scenario = new Scenario("forbiddenmoduleedge");

    assertThat(scenario.violations(ModularArchitecture::modulesDependOnlyOnAllowedModules))
        .singleElement(STRING)
        .contains("module EXTERNAL -> WORKSPACE is no allowed edge", "mcp.Tool");
    assertThat(scenario.violations(ModularArchitecture::noPackageDependsOnAHigherLayer)).isEmpty();
  }

  @Test
  void aCycleBetweenTopLevelPackagesIsReported() {
    Scenario scenario = new Scenario("cycle");

    assertThat(scenario.violations(ModularArchitecture::topLevelPackagesAreFreeOfCycles))
        .singleElement(STRING)
        .contains("Cycle detected", "common", "observability");
  }

  @Test
  void aCycleBetweenSubpackagesIsReportedUnlessTheirPackageIsTolerated() {
    Scenario scenario = new Scenario("subpackagecycle");

    assertThat(ModularArchitecture.SUBPACKAGE_CYCLES_TOLERATED).contains("query");
    assertThat(scenario.violations(ModularArchitecture::subpackagesAreFreeOfCycles))
        .singleElement(STRING)
        .contains("Cycle detected", "library.internal")
        .doesNotContain("query");
    assertThat(scenario.violations(ModularArchitecture::topLevelPackagesAreFreeOfCycles)).isEmpty();
  }

  @Test
  void anUnassignedTopLevelPackageIsNamedWithWhereToAssignIt() {
    Scenario scenario = new Scenario("unassigned");

    assertThat(scenario.violations(ModularArchitecture::everyPackageIsAssigned))
        .singleElement(STRING)
        .contains(
            "unassigned.frobnicate is assigned to no layer or no module",
            "ModularArchitecture.LAYERS",
            "ModularArchitecture.MODULES");
  }

  @Test
  void anEdgeBetweenConnectorsIsReported() {
    Scenario scenario = new Scenario("connectoredge");

    assertThat(scenario.violations(ModularArchitecture::connectorsDoNotKnowEachOther))
        .singleElement(STRING)
        .contains("rss.RssConnector", "web.WebConnector");
  }

  @Test
  void aConnectorKnownOutsideItselfIsReported() {
    Scenario scenario = new Scenario("connectorknownoutside");

    assertThat(scenario.violations(ModularArchitecture::noOneOutsideAConnectorKnowsIt))
        .anySatisfy(violation -> assertThat(violation).contains("library.Administration"))
        .anySatisfy(violation -> assertThat(violation).contains("indexing.IndexingCore"));
    assertThat(scenario.violations(ModularArchitecture::modulesDependOnlyOnAllowedModules))
        .anySatisfy(violation -> assertThat(violation).contains("module LIBRARY -> CONNECTORS"))
        .anySatisfy(violation -> assertThat(violation).contains("module KNOWLEDGE -> CONNECTORS"));
  }

  /** One example tree and the rules rooted at it. */
  private static final class Scenario {

    private final ModularArchitecture architecture;
    private final JavaClasses classes;

    Scenario(String name) {
      String root = FIXTURES + "." + name;
      this.architecture = new ModularArchitecture(root);
      this.classes = new ClassFileImporter().importPackages(root);
      assertThat(classes).as("the fixture classes of %s", name).isNotEmpty();
    }

    List<String> violations(Function<ModularArchitecture, ArchRule> rule) {
      return rule.apply(architecture).evaluate(classes).getFailureReport().getDetails();
    }
  }
}
