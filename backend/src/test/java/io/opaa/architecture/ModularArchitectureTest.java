package io.opaa.architecture;

import static io.opaa.architecture.ModularArchitecture.ALLOWED_MODULE_EDGES;
import static io.opaa.architecture.ModularArchitecture.LAYERS;
import static io.opaa.architecture.ModularArchitecture.MODULES;
import static io.opaa.architecture.ModularArchitecture.SUBPACKAGE_CYCLES_TOLERATED;
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

  private static final ModularArchitecture ARCHITECTURE = new ModularArchitecture("io.opaa");

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
  void connectorsDoNotKnowEachOther() {
    ARCHITECTURE.connectorsDoNotKnowEachOther().check(mainClasses);
  }

  @Test
  void noOneOutsideAConnectorKnowsIt() {
    ARCHITECTURE.noOneOutsideAConnectorKnowsIt().check(mainClasses);
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

  /** A package whose subpackage cycle is resolved leaves the tolerated list and is held to it. */
  @Test
  void everyToleratedSubpackageCycleStillExists() {
    assertThat(SUBPACKAGE_CYCLES_TOLERATED)
        .as("remove a package without a subpackage cycle from SUBPACKAGE_CYCLES_TOLERATED")
        .allMatch(
            topLevel ->
                ARCHITECTURE
                    .subpackagesAreFreeOfCyclesIn(topLevel)
                    .evaluate(mainClasses)
                    .hasViolation());
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
