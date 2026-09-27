package io.opaa.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static io.opaa.architecture.ModularArchitecture.Module.APP;
import static io.opaa.architecture.ModularArchitecture.Module.ASSISTANT;
import static io.opaa.architecture.ModularArchitecture.Module.CONNECTORS;
import static io.opaa.architecture.ModularArchitecture.Module.EXTERNAL;
import static io.opaa.architecture.ModularArchitecture.Module.FOUNDATION;
import static io.opaa.architecture.ModularArchitecture.Module.IDENTITY;
import static io.opaa.architecture.ModularArchitecture.Module.KNOWLEDGE;
import static io.opaa.architecture.ModularArchitecture.Module.LIBRARY;
import static io.opaa.architecture.ModularArchitecture.Module.RIGHTS;
import static io.opaa.architecture.ModularArchitecture.Module.WORKSPACE;
import static java.util.Map.entry;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The logical modules of the backend and the layering of its top-level packages, as ArchUnit rules
 * over a root package. {@link ModularArchitectureTest} applies them to the main classes under
 * {@code io.opaa}; {@link ModularArchitectureFixtureTest} to example trees that break one rule
 * each. Only compiled dependencies count: a type named in Javadoc alone is none.
 */
final class ModularArchitecture {

  /** The layer of the classes directly in the root package ({@code OpaaApplication}). */
  static final String ROOT = "(root)";

  /**
   * The top-level packages, lowest first. A package depends only on itself and on packages before
   * it; a new top-level package is inserted above everything it uses.
   */
  static final List<String> LAYERS =
      List.of(
          "common",
          "observability",
          "organization",
          "notification",
          "security",
          "ratelimit",
          "audit",
          "branding",
          "mail",
          "auth",
          "permission",
          "asset",
          "group",
          "sourceaccess",
          "s3",
          "knowledge",
          "space",
          "succession",
          "revision",
          "diagnosticaccess",
          "llm",
          "indexing",
          "library",
          "health",
          "prompt",
          "chat",
          "query",
          "search",
          "searchadmin",
          "externalaccess",
          "mcp",
          "api",
          "config",
          ROOT);

  /** The logical modules, in an order in which every allowed edge points to an earlier one. */
  enum Module {
    FOUNDATION,
    IDENTITY,
    RIGHTS,
    KNOWLEDGE,
    CONNECTORS,
    WORKSPACE,
    LIBRARY,
    ASSISTANT,
    EXTERNAL,
    APP
  }

  /** The module of every top-level package; the connectors are carved out of {@code indexing}. */
  static final Map<String, Module> MODULES =
      Map.ofEntries(
          entry("common", FOUNDATION),
          entry("observability", FOUNDATION),
          entry("organization", FOUNDATION),
          entry("notification", FOUNDATION),
          entry("security", FOUNDATION),
          entry("ratelimit", FOUNDATION),
          entry("sourceaccess", FOUNDATION),
          entry("s3", FOUNDATION),
          entry("audit", IDENTITY),
          entry("branding", IDENTITY),
          entry("mail", IDENTITY),
          entry("auth", IDENTITY),
          entry("permission", RIGHTS),
          entry("asset", RIGHTS),
          entry("group", RIGHTS),
          entry("succession", RIGHTS),
          entry("knowledge", KNOWLEDGE),
          entry("llm", KNOWLEDGE),
          entry("indexing", KNOWLEDGE),
          entry("space", WORKSPACE),
          entry("revision", WORKSPACE),
          entry("diagnosticaccess", WORKSPACE),
          entry("library", LIBRARY),
          entry("prompt", ASSISTANT),
          entry("chat", ASSISTANT),
          entry("query", ASSISTANT),
          entry("search", ASSISTANT),
          entry("searchadmin", ASSISTANT),
          entry("health", ASSISTANT),
          entry("externalaccess", EXTERNAL),
          entry("mcp", EXTERNAL),
          entry("api", APP),
          entry("config", APP),
          entry(ROOT, APP));

  /**
   * The module edges the code may use. A new edge is a deliberate decision, justified in its pull
   * request. No module reaches {@link Module#CONNECTORS}: they are found by component scanning.
   */
  static final Map<Module, Set<Module>> ALLOWED_MODULE_EDGES =
      Map.of(
          FOUNDATION, EnumSet.noneOf(Module.class),
          IDENTITY, EnumSet.of(FOUNDATION),
          RIGHTS, EnumSet.of(FOUNDATION, IDENTITY),
          KNOWLEDGE, EnumSet.of(FOUNDATION, IDENTITY, RIGHTS),
          CONNECTORS, EnumSet.of(FOUNDATION, KNOWLEDGE),
          WORKSPACE, EnumSet.of(FOUNDATION, IDENTITY, RIGHTS, KNOWLEDGE),
          LIBRARY, EnumSet.of(FOUNDATION, IDENTITY, RIGHTS, KNOWLEDGE),
          ASSISTANT, EnumSet.of(FOUNDATION, IDENTITY, RIGHTS, KNOWLEDGE, WORKSPACE, LIBRARY),
          EXTERNAL, EnumSet.of(FOUNDATION, IDENTITY, RIGHTS, KNOWLEDGE, LIBRARY, ASSISTANT),
          APP,
              EnumSet.of(
                  FOUNDATION,
                  IDENTITY,
                  RIGHTS,
                  KNOWLEDGE,
                  WORKSPACE,
                  LIBRARY,
                  ASSISTANT,
                  EXTERNAL));

  /**
   * Top-level packages whose subpackages still form a cycle among themselves, typically with the
   * package itself; every other package's subpackages must stay free of cycles.
   */
  static final Set<String> SUBPACKAGE_CYCLES_TOLERATED =
      Set.of("auth", "group", "indexing", "query", "externalaccess");

  /** Every direct subpackage of this one, relative to the root, is a connector. */
  static final String CONNECTOR_PARENT = "indexing.source";

  /** Packages of the separate {@code opaa-api} Gradle module: a library below all layers. */
  static final List<String> OUTSIDE_THE_LAYERING = List.of("api.dto", "api.types");

  private final String root;

  ModularArchitecture(String root) {
    this.root = root;
  }

  ArchRule everyPackageIsAssigned() {
    return classes()
        .that(areInTheRoot())
        .should(beAssignedToALayerAndAModule())
        .allowEmptyShould(true);
  }

  ArchRule noPackageDependsOnAHigherLayer() {
    return classes()
        .that(areInTheRoot())
        .should(
            dependOnly(
                "depend on no higher layer",
                (origin, target) -> {
                  int from = indexOf(layerOf(origin));
                  int to = indexOf(layerOf(target));
                  return from < 0 || to <= from
                      ? null
                      : layerOf(origin)
                          + " -> "
                          + layerOf(target)
                          + " points upward in ModularArchitecture.LAYERS";
                }))
        .allowEmptyShould(true);
  }

  ArchRule modulesDependOnlyOnAllowedModules() {
    return classes()
        .that(areInTheRoot())
        .should(
            dependOnly(
                "depend only on the modules ModularArchitecture.ALLOWED_MODULE_EDGES allows",
                (origin, target) -> {
                  Module from = moduleOf(origin);
                  Module to = moduleOf(target);
                  return from == null
                          || to == null
                          || from == to
                          || ALLOWED_MODULE_EDGES.get(from).contains(to)
                      ? null
                      : "module "
                          + from
                          + " -> "
                          + to
                          + " is no allowed edge in ModularArchitecture.ALLOWED_MODULE_EDGES";
                }))
        .allowEmptyShould(true);
  }

  ArchRule topLevelPackagesAreFreeOfCycles() {
    return slices().matching(root + ".(*)..").should().beFreeOfCycles().allowEmptyShould(true);
  }

  ArchRule subpackagesAreFreeOfCycles() {
    return subpackageCycles(topLevel -> !SUBPACKAGE_CYCLES_TOLERATED.contains(topLevel));
  }

  /** The cycle rule for the subpackages of the one top-level package {@code topLevel}. */
  ArchRule subpackagesAreFreeOfCyclesIn(String topLevel) {
    return subpackageCycles(topLevel::equals);
  }

  private ArchRule subpackageCycles(Predicate<String> coveredTopLevel) {
    return slices()
        .assignedFrom(
            new SliceAssignment() {
              @Override
              public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
                String relative = relative(javaClass.getPackageName());
                return relative == null
                        || relative.isEmpty()
                        || !coveredTopLevel.test(topLevel(relative))
                    ? SliceIdentifier.ignore()
                    : SliceIdentifier.of(javaClass.getPackageName());
              }

              @Override
              public String getDescription() {
                return "the packages below " + root;
              }
            })
        .should()
        .beFreeOfCycles()
        .allowEmptyShould(true);
  }

  ArchRule connectorsDoNotKnowEachOther() {
    return slices()
        .matching(root + "." + CONNECTOR_PARENT + ".(*)..")
        .should()
        .notDependOnEachOther()
        .because("a connector reaches shared code only through the indexing core")
        .allowEmptyShould(true);
  }

  ArchRule noOneOutsideAConnectorKnowsIt() {
    DescribedPredicate<JavaClass> connector =
        DescribedPredicate.describe("are connector classes", this::isConnector);
    return noClasses()
        .that(DescribedPredicate.not(connector))
        .should()
        .dependOnClassesThat(connector)
        .because(
            "the core, the administration and the API reach a connector only through the"
                + " SourceConnectorRegistry and the capabilities it hands out")
        .allowEmptyShould(true);
  }

  List<ArchRule> all() {
    return List.of(
        everyPackageIsAssigned(),
        noPackageDependsOnAHigherLayer(),
        modulesDependOnlyOnAllowedModules(),
        topLevelPackagesAreFreeOfCycles(),
        subpackagesAreFreeOfCycles(),
        connectorsDoNotKnowEachOther(),
        noOneOutsideAConnectorKnowsIt());
  }

  /** The layer of {@code javaClass}, or {@code null} outside the root or the layering. */
  String layerOf(JavaClass javaClass) {
    String relative = relative(javaClass.getBaseComponentType().getPackageName());
    if (relative == null || isOutsideTheLayering(relative)) {
      return null;
    }
    return relative.isEmpty() ? ROOT : topLevel(relative);
  }

  /** The module of {@code javaClass}, or {@code null} outside the root or unassigned. */
  Module moduleOf(JavaClass javaClass) {
    if (isConnector(javaClass)) {
      return CONNECTORS;
    }
    String layer = layerOf(javaClass);
    return layer == null ? null : MODULES.get(layer);
  }

  boolean isConnector(JavaClass javaClass) {
    String relative = relative(javaClass.getBaseComponentType().getPackageName());
    return relative != null && relative.startsWith(CONNECTOR_PARENT + ".");
  }

  private DescribedPredicate<JavaClass> areInTheRoot() {
    return DescribedPredicate.describe(
        "are in " + root, javaClass -> relative(javaClass.getPackageName()) != null);
  }

  private ArchCondition<JavaClass> beAssignedToALayerAndAModule() {
    return new ArchCondition<>("be assigned to a layer and a module") {
      private final Set<String> unassigned = new TreeSet<>();

      @Override
      public void check(JavaClass javaClass, ConditionEvents events) {
        String layer = layerOf(javaClass);
        if (layer != null && !(LAYERS.contains(layer) && MODULES.containsKey(layer))) {
          unassigned.add(layer);
        }
      }

      @Override
      public void finish(ConditionEvents events) {
        unassigned.forEach(
            layer ->
                events.add(
                    SimpleConditionEvent.violated(
                        layer,
                        root
                            + "."
                            + layer
                            + " is assigned to no layer or no module: insert \""
                            + layer
                            + "\" into ModularArchitecture.LAYERS above every package it uses,"
                            + " and into ModularArchitecture.MODULES")));
      }
    };
  }

  /** A condition over every dependency into the root; {@code violation} names a forbidden one. */
  private ArchCondition<JavaClass> dependOnly(String description, EdgeCheck violation) {
    return new ArchCondition<>(description) {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
          String reason = violation.reasonAgainst(origin, dependency.getTargetClass());
          if (reason != null) {
            events.add(
                SimpleConditionEvent.violated(
                    dependency, reason + ": " + dependency.getDescription()));
          }
        }
      }
    };
  }

  @FunctionalInterface
  private interface EdgeCheck {
    /** Why the edge from {@code origin} to {@code target} is forbidden, or {@code null}. */
    String reasonAgainst(JavaClass origin, JavaClass target);
  }

  /** {@code packageName} relative to the root, {@code ""} for the root, {@code null} outside. */
  private String relative(String packageName) {
    if (packageName.equals(root)) {
      return "";
    }
    return packageName.startsWith(root + ".") ? packageName.substring(root.length() + 1) : null;
  }

  /** The position of {@code layer} in {@link #LAYERS}, {@code -1} for none. */
  private static int indexOf(String layer) {
    return layer == null ? -1 : LAYERS.indexOf(layer);
  }

  private static String topLevel(String relative) {
    int dot = relative.indexOf('.');
    return dot < 0 ? relative : relative.substring(0, dot);
  }

  private static boolean isOutsideTheLayering(String relative) {
    return OUTSIDE_THE_LAYERING.stream()
        .anyMatch(outside -> relative.equals(outside) || relative.startsWith(outside + "."));
  }
}
