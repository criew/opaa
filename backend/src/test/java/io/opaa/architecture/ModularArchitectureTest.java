package io.opaa.architecture;

import static io.opaa.architecture.ModularArchitecture.ALLOWED_MODULE_EDGES;
import static io.opaa.architecture.ModularArchitecture.API;
import static io.opaa.architecture.ModularArchitecture.KNOWN_SUBPACKAGE_CYCLE_EDGES;
import static io.opaa.architecture.ModularArchitecture.LAYERS;
import static io.opaa.architecture.ModularArchitecture.MODULES;
import static io.opaa.architecture.ModularArchitecture.SHARED_API_CLASSES;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import io.opaa.architecture.ModularArchitecture.Module;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Holds the main classes of the backend to the logical modules and the layering declared in {@link
 * ModularArchitecture}. Reads the compiled classes directly, without a Spring context.
 */
class ModularArchitectureTest {

  private static final ModularArchitecture ARCHITECTURE =
      new ModularArchitecture("io.opaa", KNOWN_SUBPACKAGE_CYCLE_EDGES);

  private static JavaClasses mainClasses;

  @BeforeAll
  static void importMainClasses() {
    mainClasses = MainClasses.get();
  }

  @Test
  void everyTopLevelPackageIsAssignedToALayerAndAModule() {
    ARCHITECTURE.everyPackageIsAssigned().check(mainClasses);
  }

  @Test
  void noPackageDependsOnAHigherLayer() {
    ARCHITECTURE.noPackageDependsOnAHigherLayer().check(mainClasses);
  }

  @Test
  void modulesDependOnlyOnAllowedModules() {
    ARCHITECTURE.modulesDependOnlyOnAllowedModules().check(mainClasses);
  }

  @Test
  void topLevelPackagesAreFreeOfCycles() {
    ARCHITECTURE.topLevelPackagesAreFreeOfCycles().check(mainClasses);
  }

  @Test
  void subpackagesAreFreeOfCycles() {
    ARCHITECTURE.subpackagesAreFreeOfCycles().check(mainClasses);
  }

  @Test
  void theIndexingCoreDependsOnlyDownward() {
    ARCHITECTURE.theIndexingCoreDependsOnlyDownward().check(mainClasses);
  }

  @Test
  void everyIndexingPackageIsInTheCore() {
    ARCHITECTURE.everyIndexingPackageIsInTheCore().check(mainClasses);
  }

  @Test
  void theFileSyncKnowsNoProvider() {
    ARCHITECTURE.theFileSyncKnowsNoProvider().check(mainClasses);
  }

  @Test
  void theConnectionPackagesDependOnlyDownward() {
    ARCHITECTURE.theConnectionPackagesDependOnlyDownward().check(mainClasses);
  }

  /** Guards the premise of the connection rule: every package it orders still has classes. */
  @Test
  void everyConnectionPackageHasClasses() {
    Set<String> packages =
        mainClasses.stream()
            .map(JavaClass::getPackageName)
            .filter(name -> name.startsWith("io.opaa."))
            .map(name -> name.substring("io.opaa.".length()))
            .collect(Collectors.toSet());
    assertThat(packages).containsAll(ModularArchitecture.CONNECTION_PACKAGES);
  }

  @Test
  void connectorsDoNotKnowEachOther() {
    ARCHITECTURE.connectorsDoNotKnowEachOther().check(mainClasses);
  }

  @Test
  void noOneOutsideAConnectorKnowsIt() {
    ARCHITECTURE.noOneOutsideAConnectorKnowsIt().check(mainClasses);
  }

  @Test
  void connectorsTakeTheirSourceConfigurationFromTheCore() {
    ARCHITECTURE.connectorsTakeTheirSourceConfigurationFromTheCore().check(mainClasses);
  }

  @Test
  void theForeignContextNeverUsesTheOwnFormula() {
    ARCHITECTURE.theForeignContextNeverUsesTheOwnFormula().check(mainClasses);
  }

  @Test
  void theSecretPortStaysWithTheCore() {
    ARCHITECTURE.theSecretPortStaysWithTheCore().check(mainClasses);
  }

  @Test
  void onlyTheChangeGateCallsTheConnectorChangeHooks() {
    ARCHITECTURE.onlyTheChangeGateCallsTheConnectorChangeHooks().check(mainClasses);
  }

  @Test
  void theLibrarySecretIsReadInOnePlace() {
    ARCHITECTURE.theLibrarySecretIsReadInOnePlace().check(mainClasses);
  }

  @Test
  void theSecretStoreIsUsedOnlyInConnections() {
    ARCHITECTURE.theSecretStoreIsUsedOnlyInConnections().check(mainClasses);
  }

  @Test
  void theConnectorReleaseIsDecidedInConnections() {
    ARCHITECTURE.theConnectorReleaseIsDecidedInConnections().check(mainClasses);
  }

  @Test
  void webClassesResideInAWebPackage() {
    ARCHITECTURE.webClassesResideInAWebPackage().check(mainClasses);
  }

  @Test
  void apiHoldsOnlyItsListedClasses() {
    ARCHITECTURE.apiHoldsOnlyItsListedClasses().check(mainClasses);
  }

  @Test
  void onlyTheWebLayerAndAppDependOnAWebPackage() {
    ARCHITECTURE.onlyTheWebLayerAndAppDependOnAWebPackage().check(mainClasses);
  }

  /** A shared class that is gone leaves the list, so the list names what {@code api} holds. */
  @Test
  void everySharedApiClassIsInApi() {
    Set<String> inApi =
        mainClasses.stream()
            .filter(javaClass -> javaClass.getPackageName().equals("io.opaa." + API))
            .map(JavaClass::getSimpleName)
            .collect(Collectors.toSet());
    assertThat(inApi)
        .as("remove a class that is gone from SHARED_API_CLASSES")
        .containsAll(SHARED_API_CLASSES);
  }

  @Test
  void theLayeringAndTheModulesNameTheSamePackages() {
    assertThat(MODULES.keySet()).containsExactlyInAnyOrderElementsOf(LAYERS);
  }

  /** Keeps the module graph acyclic: the enum order is a topological order of the edges. */
  @Test
  void everyAllowedModuleEdgePointsToAnEarlierModule() {
    ALLOWED_MODULE_EDGES.forEach(
        (from, targets) ->
            assertThat(targets)
                .as("allowed edges of %s", from)
                .allMatch(to -> to.ordinal() < from.ordinal()));
    assertThat(ALLOWED_MODULE_EDGES.keySet()).containsExactlyInAnyOrder(Module.values());
  }

  /** The allowed edges document the code: an edge no longer used is removed from the list. */
  @Test
  void everyAllowedModuleEdgeIsInUse() {
    Map<Module, Set<Module>> used = new TreeMap<>();
    for (JavaClass origin : mainClasses) {
      for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
        Module from = ARCHITECTURE.moduleOf(origin);
        Module to = ARCHITECTURE.moduleOf(dependency.getTargetClass());
        if (from != null && to != null && from != to) {
          used.computeIfAbsent(from, module -> new TreeSet<>()).add(to);
        }
      }
    }
    ALLOWED_MODULE_EDGES.forEach(
        (from, targets) ->
            assertThat(used.getOrDefault(from, Set.of()))
                .as("edges module %s uses - remove an unused one from ALLOWED_MODULE_EDGES", from)
                .containsAll(targets));
  }

  /** An edge that no longer lies on a cycle leaves the list, so the cycle cannot return. */
  @Test
  void everyKnownSubpackageCycleEdgeStillLiesOnACycle() {
    assertThat(ARCHITECTURE.subpackageCycleEdges(mainClasses).keySet())
        .as("remove an edge that lies on no cycle any more from KNOWN_SUBPACKAGE_CYCLE_EDGES")
        .containsAll(KNOWN_SUBPACKAGE_CYCLE_EDGES);
  }

  /** Guards the premise: a rule over an empty or stale package list proves nothing. */
  @Test
  void everyLayerHasClasses() {
    Set<String> layers =
        mainClasses.stream()
            .map(ARCHITECTURE::layerOf)
            .filter(layer -> layer != null)
            .collect(Collectors.toSet());
    assertThat(layers)
        .as("a top-level package that is gone is removed from LAYERS and MODULES as well")
        .containsAll(LAYERS);
  }

  /** Guards the premise of the core rule: every package it orders still has classes. */
  @Test
  void everyIndexingCorePackageHasClasses() {
    Set<String> packages =
        mainClasses.stream()
            .map(JavaClass::getPackageName)
            .filter(name -> name.startsWith("io.opaa."))
            .map(name -> name.substring("io.opaa.".length()))
            .collect(Collectors.toSet());
    assertThat(packages).containsAll(ModularArchitecture.INDEXING_CORE);
  }

  @Test
  void theConnectorsAreFoundBelowTheSourcePackage() {
    Set<String> connectors =
        mainClasses.stream()
            .filter(ARCHITECTURE::isConnector)
            .map(javaClass -> javaClass.getPackageName().split("\\.")[4])
            .collect(Collectors.toSet());
    assertThat(connectors).containsAll(List.of("s3", "confluence", "rss", "web", "filesystem"));
  }
}
