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

  /**
   * {@code connection.profile -> connection} names the root package, {@code connection.log ->
   * connection.profile} points upward, {@code connection.misc} is not ordered; the web package may
   * use all of them.
   */
  @Test
  void aConnectionSubpackageThatNamesItsRootOrIsUnorderedIsReported() {
    Scenario scenario = new Scenario("connectionorder");

    assertThat(scenario.violations(ModularArchitecture::theConnectionPackagesDependOnlyDownward))
        .hasSize(3)
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains("connection.log -> connection.profile points upward", "log.Entry"))
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains(
                        "connection.profile -> connection leaves the connection subpackages",
                        "profile.Profile"))
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains(
                        "connectionorder.connection.misc is not ordered", "CONNECTION_PACKAGES"))
        .noneSatisfy(violation -> assertThat(violation).contains("ProfileApi"));
  }

  /**
   * ADR-0041, Entscheidung 2: neither the core nor a connector knows connections, and connections
   * never reaches library; library reaching connections passes.
   */
  @Test
  void anEdgeIntoConnectionsFromBelowOrOutOfItUpwardIsReported() {
    Scenario scenario = new Scenario("connectionedges");

    assertThat(scenario.violations(ModularArchitecture::modulesDependOnlyOnAllowedModules))
        .hasSize(3)
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains("module KNOWLEDGE -> CONNECTIONS is no allowed edge", "IndexingCore"))
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains(
                        "module CONNECTORS -> CONNECTIONS is no allowed edge", "WebConnector"))
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains("module CONNECTIONS -> LIBRARY is no allowed edge", "Resolver"))
        .noneSatisfy(violation -> assertThat(violation).contains("LibraryAdministration.resolver"));
    assertThat(scenario.violations(ModularArchitecture::noPackageDependsOnAHigherLayer))
        .anySatisfy(
            violation -> assertThat(violation).contains("indexing -> connection points upward"));
  }

  /**
   * library reads the scoped capability and calls the scoped checks itself; connections and rights
   * may, an unscoped check from workspace passes.
   */
  @Test
  void aConnectorReleaseDecidedOutsideConnectionsIsReported() {
    Scenario scenario = new Scenario("connectorrelease");

    assertThat(scenario.violations(ModularArchitecture::theConnectorReleaseIsDecidedInConnections))
        .hasSize(3)
        .allSatisfy(violation -> assertThat(violation).contains("library.LibraryCreation"))
        .anySatisfy(
            violation -> assertThat(violation).contains("Capability.CREATE_CONNECTOR_LIBRARY"))
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains("CapabilityService.hasCapability(", "Capability, java.lang.String)"))
        .anySatisfy(violation -> assertThat(violation).contains("CapabilityService.scopesOf("));
  }

  /** library calls two hooks itself, also through an implementation; the gate may. */
  @Test
  void aConnectorChangeHookCalledOutsideTheGateIsReported() {
    Scenario scenario = new Scenario("changegate");

    assertThat(
            scenario.violations(ModularArchitecture::onlyTheChangeGateCallsTheConnectorChangeHooks))
        .hasSize(2)
        .allSatisfy(violation -> assertThat(violation).contains("library.LibraryUpdate"))
        .anySatisfy(violation -> assertThat(violation).contains("SourceConnector.validateChange("))
        .anySatisfy(violation -> assertThat(violation).contains("ProbeConnector.onSourceChanged("));
  }

  /**
   * A sweep deletes documents, also by method reference and through a modifying query that deletes
   * under another name; the document service may, and a modifying update is no deletion.
   */
  @Test
  void aDocumentDeletedOutsideTheKnownClassesIsReported() {
    Scenario scenario = new Scenario("documentdelete");

    assertThat(scenario.violations(ModularArchitecture::onlyTheKnownClassesDeleteDocuments))
        .hasSize(3)
        .allSatisfy(violation -> assertThat(violation).contains("library.FolderSweep"))
        .anySatisfy(violation -> assertThat(violation).contains("DocumentRepository.delete("))
        .anySatisfy(
            violation -> assertThat(violation).contains("DocumentRepository.deleteByLibraryId("))
        .anySatisfy(violation -> assertThat(violation).contains("DocumentRepository.purgeStale("));
  }

  /** An administrative sweep removes through the cleanup service; the file sync may. */
  @Test
  void aRemovalThroughTheCleanupServiceOutsideTheRunIsReported() {
    Scenario scenario = new Scenario("documentdelete");

    assertThat(scenario.violations(ModularArchitecture::onlyTheRunRemovesThroughTheCleanupService))
        .hasSize(2)
        .allSatisfy(violation -> assertThat(violation).contains("library.AdminSweep"))
        .anySatisfy(
            violation ->
                assertThat(violation).contains("StaleDocumentCleanupService.cleanupVanished("))
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains("StaleDocumentCleanupService.removeWithAttachments"));
  }

  /** connections reads the declared support beside the one place; library may. */
  @Test
  void theProfileSupportReadOutsideItsPlaceIsReported() {
    Scenario scenario = new Scenario("profilesupport");

    assertThat(scenario.violations(ModularArchitecture::theProfileSupportIsReadInOnePlace))
        .hasSize(1)
        .allSatisfy(
            violation ->
                assertThat(violation).contains("connection.ConnectorRelease", "support()"));
  }

  /** library reads the secret, also by method reference; knowledge and the store may. */
  @Test
  void theLibrarySecretReadOutsideTheStoreIsReported() {
    Scenario scenario = new Scenario("librarysecret");

    assertThat(scenario.violations(ModularArchitecture::theLibrarySecretIsReadInOnePlace))
        .hasSize(2)
        .allSatisfy(
            violation ->
                assertThat(violation)
                    .contains("library.LibraryAdministration", "getSourceCredentials"));
  }

  /** library asks the store directly instead of the port; connections may. */
  @Test
  void theSecretStoreUsedOutsideConnectionsIsReported() {
    Scenario scenario = new Scenario("librarysecret");

    assertThat(scenario.violations(ModularArchitecture::theSecretStoreIsUsedOnlyInConnections))
        .isNotEmpty()
        .allSatisfy(
            violation ->
                assertThat(violation).contains("library.SecretShortcut", "ConnectionSecrets"));
  }

  /** The answer path holds the secret port; the core and the library administration may. */
  @Test
  void theSecretPortHeldOutsideTheCoreIsReported() {
    Scenario scenario = new Scenario("secretport");

    assertThat(scenario.violations(ModularArchitecture::theSecretPortStaysWithTheCore))
        .singleElement(STRING)
        .contains("query.SourceAnswers", "SourceConnectionResolver");
  }

  /**
   * In {@code diagnosticaccess}: the library formula as call and method reference, and the asset
   * shell's formula in three shapes. In {@code searchadmin}: a method and a lambda that hold the
   * foreign context. The narrower method, the own context of the diagnosis and the own search pass.
   */
  @Test
  void theOwnFormulaInTheForeignContextIsReported() {
    Scenario scenario = new Scenario("foreigncontext");

    List<String> violations =
        scenario.violations(ModularArchitecture::theForeignContextNeverUsesTheOwnFormula);

    assertThat(violations)
        .hasSize(7)
        .noneSatisfy(violation -> assertThat(violation).contains("InForeignContext"))
        .noneSatisfy(violation -> assertThat(violation).contains("Diagnosis.own("))
        .noneSatisfy(violation -> assertThat(violation).contains("OwnSearch"));
    assertThat(violations.stream().filter(v -> v.contains("diagnosticaccess.web.ForeignView")))
        .hasSize(2);
    assertThat(violations)
        .anySatisfy(v -> assertThat(v).contains("AssetFormula", "readableAssetIds("))
        .anySatisfy(v -> assertThat(v).contains("AssetFormula", "readableAssets("))
        .anySatisfy(v -> assertThat(v).contains("AssetFormula", "effectiveRoles("))
        .anySatisfy(v -> assertThat(v).contains("Diagnosis.foreign("))
        .anySatisfy(v -> assertThat(v).contains("Diagnosis.foreignLambda("));
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

  /**
   * {@code indexing.filesync} reaches the provider package, a connector and a third-party class.
   */
  @Test
  void aFileSyncThatKnowsAProviderIsReported() {
    Scenario scenario = new Scenario("filesyncprovider");

    assertThat(scenario.violations(ModularArchitecture::theFileSyncKnowsNoProvider))
        .hasSize(3)
        .anySatisfy(
            violation ->
                assertThat(violation).contains("-> s3 is no package the file sync may use"))
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains("-> indexing.source.s3 is no package the file sync may use"))
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains("-> org.springframework.util.StringUtils is a third-party client"));
    assertThat(scenario.violations(ModularArchitecture::theIndexingCoreDependsOnlyDownward))
        .singleElement(STRING)
        .contains("indexing.filesync -> indexing.source.s3 leaves the indexing core");
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

  /**
   * A call, a method reference, the static read of the stored settings, a held resolver, a created
   * one and a read from the file sync are reported; the push secret, the filesystem path and the
   * core's own resolver pass.
   */
  @Test
  void aConnectorReadingItsSourceConfigurationFromTheLibraryIsReported() {
    Scenario scenario = new Scenario("connectorreadslibrary");

    assertThat(
            scenario.violations(
                ModularArchitecture::connectorsTakeTheirSourceConfigurationFromTheCore))
        .hasSize(6)
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains("filesync.FileSyncRun", "KnowledgeLibrary.getSourceProxy"))
        .anySatisfy(
            violation -> assertThat(violation).contains("web.WebRun", "SourceConnectionResolver"))
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains("upload.UploadRun", "LibrarySourceConnectionResolver.<init>"))
        .anySatisfy(
            violation ->
                assertThat(violation).contains("s3.S3Run", "KnowledgeLibrary.getSourceCredentials"))
        .anySatisfy(
            violation ->
                assertThat(violation).contains("rss.FeedRun", "KnowledgeLibrary.getSourceUrl"))
        .anySatisfy(
            violation ->
                assertThat(violation)
                    .contains("confluence.ConfluenceSettings", "ConnectorData.storedIn"))
        .noneSatisfy(violation -> assertThat(violation).contains("getWebhookSecret"))
        .noneSatisfy(violation -> assertThat(violation).contains("getSourcePath"))
        .noneSatisfy(violation -> assertThat(violation).contains("source.SourceResolver"));
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
