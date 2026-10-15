package io.opaa.architecture;

import static io.opaa.architecture.ModularArchitecture.ALLOWED_MODULE_EDGES;
import static io.opaa.architecture.ModularArchitecture.API;
import static io.opaa.architecture.ModularArchitecture.LAYERS;
import static io.opaa.architecture.ModularArchitecture.MODULES;
import static io.opaa.architecture.ModularArchitecture.SHARED_API_CLASSES;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import io.opaa.architecture.ModularArchitecture.Module;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Holds the main classes of the backend to the logical modules and the layering declared in {@link
 * ModularArchitecture}. Reads the compiled classes directly, without a Spring context.
 *
 * <p>Beside modules, edges, layers and cycles it holds the order inside {@code indexing} and {@code
 * connection}, the isolation of the connectors, the place of the web layer and the release of
 * connector libraries. Every list must match the code: an unassigned package fails naming both
 * lists, and a listed package, an allowed edge or a named class that is gone fails as well.
 */
class ModularArchitectureTest {

  private static final ModularArchitecture ARCHITECTURE = new ModularArchitecture("io.opaa");

  /** A class, a nested class or a member below the root, relative to it. */
  private static final Pattern NAMED_CODE_UNIT =
      Pattern.compile("([a-z][a-z0-9]*\\.)+[A-Z]\\w*(\\$\\w+)*(#\\w+)?");

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
  void everyOutboundClientIsRegistered() {
    ARCHITECTURE.everyOutboundClientIsRegistered().check(mainClasses);
  }

  @Test
  void everyConfigurationPropertiesClassIsRegisteredOnce() {
    ARCHITECTURE.everyConfigurationPropertiesClassIsRegisteredOnce().check(mainClasses);
  }

  @Test
  void theForeignContextNeverUsesTheOwnFormula() {
    ARCHITECTURE.theForeignContextNeverUsesTheOwnFormula().check(mainClasses);
  }

  @Test
  void privateAssetsAreAuditedNeutrally() {
    ARCHITECTURE.privateAssetsAreAuditedNeutrally().check(mainClasses);
  }

  @Test
  void personalUsageIsReadOnlyByItsOwner() {
    ARCHITECTURE.personalUsageIsReadOnlyByItsOwner().check(mainClasses);
  }

  @Test
  void privateLibrariesAreNotEnumeratedOutsideListedClasses() {
    ARCHITECTURE.privateLibrariesAreNotEnumeratedOutsideListedClasses().check(mainClasses);
  }

  @Test
  void theSecretPortStaysWithTheCore() {
    ARCHITECTURE.theSecretPortStaysWithTheCore().check(mainClasses);
  }

  @Test
  void theProfileSupportIsReadInOnePlace() {
    ARCHITECTURE.theProfileSupportIsReadInOnePlace().check(mainClasses);
  }

  @Test
  void onlyTheKnownClassesDeleteDocuments() {
    ARCHITECTURE.onlyTheKnownClassesDeleteDocuments().check(mainClasses);
  }

  @Test
  void onlyTheRunRemovesThroughTheCleanupService() {
    ARCHITECTURE.onlyTheRunRemovesThroughTheCleanupService().check(mainClasses);
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
  void theAdministrationNeverSeesAConnectedPerson() {
    ARCHITECTURE.theAdministrationNeverSeesAConnectedPerson().check(mainClasses);
  }

  @Test
  void personNumbersLeaveOnlyMasked() {
    ARCHITECTURE.personNumbersLeaveOnlyMasked().check(mainClasses);
  }

  @Test
  void aConnectionIsEstablishedOnlyAfterItsSignIn() {
    ARCHITECTURE.aConnectionIsEstablishedOnlyAfterItsSignIn().check(mainClasses);
  }

  @Test
  void theProfileRegistrationLeavesOnlyToTheSignIn() {
    ARCHITECTURE.theProfileRegistrationLeavesOnlyToTheSignIn().check(mainClasses);
  }

  @Test
  void theAuthorizationServerIsReachedOnlyThroughTheOAuthClient() {
    ARCHITECTURE.theAuthorizationServerIsReachedOnlyThroughTheOAuthClient().check(mainClasses);
  }

  @Test
  void connectorPathsSeeOnlyConnectorProfiles() {
    ARCHITECTURE.connectorPathsSeeOnlyConnectorProfiles().check(mainClasses);
  }

  @Test
  void refreshTokensStayInTheTokenStore() {
    ARCHITECTURE.refreshTokensStayInTheTokenStore().check(mainClasses);
  }

  @Test
  void grantsAreRevokedOnlyAfterTheCommit() {
    ARCHITECTURE.grantsAreRevokedOnlyAfterTheCommit().check(mainClasses);
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

  /**
   * Guards the premise of every rule that names a class, a nested class, a method or a field: an
   * entry that resolves to nothing, after a rename say, lets its rule check nothing. The names come
   * from every {@code static final} constant of {@link ModularArchitecture}, so a new list needs no
   * entry here.
   */
  @Test
  void everyNamedCodeUnitExists() {
    Set<String> names = namedCodeUnits();
    assertThat(names)
        .contains(
            ModularArchitecture.CLEANUP_SERVICE,
            ModularArchitecture.CONNECTOR_RELEASE,
            "connection.token.NewSecret$OAuthGrant",
            "knowledge.KnowledgeLibraryRepository#findAll",
            "indexing.document.DocumentIngestOutcomes$QuotaMessages#of");
    assertThat(ARCHITECTURE.unresolved(names))
        .as("rename or remove the entry in ModularArchitecture")
        .isEmpty();
  }

  private static Set<String> namedCodeUnits() {
    Set<String> names = new TreeSet<>();
    for (Field field : ModularArchitecture.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) {
        try {
          collectNames(field.get(null), names);
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(field.getName(), e);
        }
      }
    }
    return names;
  }

  private static void collectNames(Object value, Set<String> names) {
    if (value instanceof String name) {
      if (NAMED_CODE_UNIT.matcher(name).matches()) {
        names.add(name);
      }
    } else if (value instanceof Collection<?> values) {
      values.forEach(element -> collectNames(element, names));
    } else if (value instanceof Map<?, ?> map) {
      map.forEach(
          (key, element) -> {
            collectNames(key, names);
            collectNames(element, names);
          });
    }
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
