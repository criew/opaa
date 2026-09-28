package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Proves that every rule of {@link ModularArchitecture} fires. Each example tree under {@code
 * io.opaa.architecture.fixture.<scenario>} mirrors the top-level packages of the backend and breaks
 * one rule; the rules are applied with that scenario package as their root. Fixture controllers are
 * abstract, so the component scan of the test contexts under {@code io.opaa} never registers them.
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

  /**
   * {@code query <-> query.answer} is a known cycle; {@code query.answer <-> query.citation} is new
   * in the same package, {@code library <-> library.internal} new in a package without one.
   */
  @Test
  void aCycleBetweenSubpackagesIsReportedUnlessItsEdgesAreKnown() {
    Scenario scenario =
        new Scenario("subpackagecycle", Set.of("query -> query.answer", "query.answer -> query"));

    assertThat(scenario.violations(ModularArchitecture::subpackagesAreFreeOfCycles))
        .allSatisfy(
            violation ->
                assertThat(violation)
                    .contains("is no known edge in", "KNOWN_SUBPACKAGE_CYCLE_EDGES"))
        .map(violation -> violation.substring(0, violation.indexOf(" lies on a cycle")))
        .containsExactlyInAnyOrder(
            "library -> library.internal",
            "library.internal -> library",
            "query.answer -> query.citation",
            "query.citation -> query.answer");
    assertThat(scenario.violations(ModularArchitecture::topLevelPackagesAreFreeOfCycles)).isEmpty();
  }

  /** {@code document -> source} points upward in the core, {@code job -> indexing} leaves it. */
  @Test
  void anEdgeAgainstTheIndexingCoreOrderIsReported() {
    Scenario scenario = new Scenario("indexingcore");

    assertThat(scenario.violations(ModularArchitecture::theIndexingCoreDependsOnlyDownward))
        .hasSize(2)
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains(
                        "indexing.document -> indexing.source points upward",
                        "document.DocumentIngest"))
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains(
                        "indexing.job -> indexing leaves the indexing core", "job.IndexingJob"));
    assertThat(scenario.violations(ModularArchitecture::noPackageDependsOnAHigherLayer)).isEmpty();
  }

  /** {@code indexing.run} is neither core, root, web package nor connector. */
  @Test
  void anIndexingPackageOutsideTheCoreIsReported() {
    Scenario scenario = new Scenario("indexingunlisted");

    assertThat(scenario.violations(ModularArchitecture::everyIndexingPackageIsInTheCore))
        .singleElement(STRING)
        .contains("indexingunlisted.indexing.run is not ordered", "INDEXING_CORE");
    assertThat(scenario.violations(ModularArchitecture::theIndexingCoreDependsOnlyDownward))
        .isEmpty();
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

  /** The connector package {@code indexing.source.web} is no web package. */
  @Test
  void aWebClassOutsideAWebPackageIsReported() {
    Scenario scenario = new Scenario("weboutsideweb");

    assertThat(scenario.violations(ModularArchitecture::webClassesResideInAWebPackage))
        .hasSize(3)
        .anySatisfy(violation -> assertThat(violation).contains("library.LibraryController "))
        .anySatisfy(violation -> assertThat(violation).contains("library.LibraryResponseMapper "))
        .anySatisfy(violation -> assertThat(violation).contains("web.WebhookController "));
  }

  @Test
  void aClassInApiThatIsNotListedIsReported() {
    Scenario scenario = new Scenario("unlistedapiclass");

    assertThat(scenario.violations(ModularArchitecture::apiHoldsOnlyItsListedClasses))
        .singleElement(STRING)
        .contains("api.SpaceController is not shared by every module");
    assertThat(scenario.violations(ModularArchitecture::webClassesResideInAWebPackage)).isEmpty();
    assertThat(
            new Scenario(
                    "unlistedapiclass", Set.of(), Set.of("HealthController", "SpaceController"))
                .violations(ModularArchitecture::apiHoldsOnlyItsListedClasses))
        .isEmpty();
  }

  /** {@code library -> permission.web} passes the layering and the modules, but not this rule. */
  @Test
  void aDomainClassThatUsesAWebPackageIsReported() {
    Scenario scenario = new Scenario("domainusesweb");

    assertThat(scenario.violations(ModularArchitecture::onlyTheWebLayerAndAppDependOnAWebPackage))
        .singleElement(STRING)
        .contains("library.Library", "permission.web.GrantResponseMapper");
    assertThat(scenario.violations(ModularArchitecture::noPackageDependsOnAHigherLayer)).isEmpty();
    assertThat(scenario.violations(ModularArchitecture::modulesDependOnlyOnAllowedModules))
        .isEmpty();
  }

  /**
   * The exemption of a web package from the layering ends at its module: {@code permission.web ->
   * space} leaves RIGHTS for WORKSPACE, which the module rule reports.
   */
  @Test
  void aWebPackageThatLeavesItsModuleBreaksTheModules() {
    Scenario scenario = new Scenario("webacrossmodules");

    assertThat(scenario.violations(ModularArchitecture::modulesDependOnlyOnAllowedModules))
        .singleElement(STRING)
        .contains("module RIGHTS -> WORKSPACE is no allowed edge", "web.GrantController");
    assertThat(scenario.violations(ModularArchitecture::noPackageDependsOnAHigherLayer)).isEmpty();
  }

  /** Web packages are exempt from the layering, so a cycle between two of them is its own slice. */
  @Test
  void aCycleBetweenWebPackagesIsReported() {
    Scenario scenario = new Scenario("webcycle");

    assertThat(scenario.violations(ModularArchitecture::topLevelPackagesAreFreeOfCycles))
        .singleElement(STRING)
        .contains("Cycle detected", "auth.web", "branding.web");
    assertThat(scenario.violations(ModularArchitecture::noPackageDependsOnAHigherLayer)).isEmpty();
  }

  /** One example tree and the rules rooted at it. */
  private static final class Scenario {

    private final ModularArchitecture architecture;
    private final JavaClasses classes;

    Scenario(String name) {
      this(name, Set.of());
    }

    Scenario(String name, Set<String> knownCycleEdges) {
      this(name, knownCycleEdges, ModularArchitecture.SHARED_API_CLASSES);
    }

    Scenario(String name, Set<String> knownCycleEdges, Set<String> apiClasses) {
      String root = FIXTURES + "." + name;
      this.architecture = new ModularArchitecture(root, knownCycleEdges, apiClasses);
      this.classes = new ClassFileImporter().importPackages(root);
      assertThat(classes).as("the fixture classes of %s", name).isNotEmpty();
    }

    List<String> violations(Function<ModularArchitecture, ArchRule> rule) {
      return rule.apply(architecture).evaluate(classes).getFailureReport().getDetails();
    }
  }
}
