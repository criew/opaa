package io.opaa.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static io.opaa.architecture.ModularArchitecture.Module.APP;
import static io.opaa.architecture.ModularArchitecture.Module.ASSISTANT;
import static io.opaa.architecture.ModularArchitecture.Module.CONNECTORS;
import static io.opaa.architecture.ModularArchitecture.Module.EXTERNAL;
import static io.opaa.architecture.ModularArchitecture.Module.FORMAT;
import static io.opaa.architecture.ModularArchitecture.Module.FOUNDATION;
import static io.opaa.architecture.ModularArchitecture.Module.IDENTITY;
import static io.opaa.architecture.ModularArchitecture.Module.KNOWLEDGE;
import static io.opaa.architecture.ModularArchitecture.Module.LIBRARY;
import static io.opaa.architecture.ModularArchitecture.Module.RETRIEVAL;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Controller;

/**
 * The logical modules of the backend, the layering of its top-level packages and the place of its
 * web layer, as ArchUnit rules over a root package. {@link ModularArchitectureTest} applies them to
 * the main classes under {@code io.opaa}; {@link ModularArchitectureFixtureTest} to example trees
 * that break one rule each. Only compiled dependencies count: a type named only in Javadoc, or only
 * through a {@code static final} constant the compiler inlines, is none.
 */
public final class ModularArchitecture {

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
          "account",
          "permission",
          "asset",
          "group",
          "directory",
          "sourceaccess",
          "s3",
          "format",
          "knowledge",
          "space",
          "succession",
          "revision",
          "diagnosticaccess",
          "llm",
          "metadata",
          "indexing",
          "library",
          "retrieval",
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
  public enum Module {
    FOUNDATION,
    FORMAT,
    IDENTITY,
    RIGHTS,
    KNOWLEDGE,
    CONNECTORS,
    WORKSPACE,
    LIBRARY,
    RETRIEVAL,
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
          entry("security", FOUNDATION),
          entry("ratelimit", FOUNDATION),
          entry("sourceaccess", FOUNDATION),
          entry("s3", FOUNDATION),
          entry("format", FORMAT),
          entry("audit", IDENTITY),
          entry("branding", IDENTITY),
          entry("mail", IDENTITY),
          entry("auth", IDENTITY),
          entry("account", IDENTITY),
          entry("notification", IDENTITY),
          entry("permission", RIGHTS),
          entry("asset", RIGHTS),
          entry("group", RIGHTS),
          entry("directory", RIGHTS),
          entry("succession", RIGHTS),
          entry("knowledge", KNOWLEDGE),
          entry("llm", KNOWLEDGE),
          entry("metadata", KNOWLEDGE),
          entry("indexing", KNOWLEDGE),
          entry("space", WORKSPACE),
          entry("revision", WORKSPACE),
          entry("diagnosticaccess", WORKSPACE),
          entry("library", LIBRARY),
          entry("retrieval", RETRIEVAL),
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
   * EXTERNAL reaches RIGHTS for members a library inherits from {@code io.opaa.asset.Asset} (a
   * method reference such as {@code KnowledgeLibrary::getId} names the declaring class) and for the
   * favorite marks the token selection filters by ({@code AssetCatalogService#favoritesAmong}).
   */
  public static final Map<Module, Set<Module>> ALLOWED_MODULE_EDGES =
      Map.ofEntries(
          entry(FOUNDATION, EnumSet.noneOf(Module.class)),
          entry(FORMAT, EnumSet.of(FOUNDATION)),
          entry(IDENTITY, EnumSet.of(FOUNDATION)),
          entry(RIGHTS, EnumSet.of(FOUNDATION, IDENTITY)),
          entry(KNOWLEDGE, EnumSet.of(FOUNDATION, FORMAT, IDENTITY, RIGHTS)),
          entry(CONNECTORS, EnumSet.of(FOUNDATION, FORMAT, KNOWLEDGE)),
          entry(WORKSPACE, EnumSet.of(FOUNDATION, IDENTITY, RIGHTS, KNOWLEDGE)),
          entry(LIBRARY, EnumSet.of(FOUNDATION, FORMAT, IDENTITY, RIGHTS, KNOWLEDGE)),
          entry(RETRIEVAL, EnumSet.of(FOUNDATION, FORMAT, KNOWLEDGE)),
          entry(
              ASSISTANT,
              EnumSet.of(
                  FOUNDATION, FORMAT, IDENTITY, RIGHTS, KNOWLEDGE, WORKSPACE, LIBRARY, RETRIEVAL)),
          entry(EXTERNAL, EnumSet.of(FOUNDATION, IDENTITY, RIGHTS, KNOWLEDGE, LIBRARY, ASSISTANT)),
          entry(APP, EnumSet.of(FOUNDATION, IDENTITY, RIGHTS, KNOWLEDGE, LIBRARY)));

  /**
   * The package edges, relative to the root, that lie on a cycle between the subpackages of one
   * top-level package today. Any other edge on such a cycle - and so every new cycle - fails the
   * test; an edge that no longer lies on a cycle is removed from this list.
   */
  static final Set<String> KNOWN_SUBPACKAGE_CYCLE_EDGES =
      Set.of(
          "externalaccess -> externalaccess.token",
          "externalaccess.token -> externalaccess",
          "query -> query.spike",
          "query.spike -> query");

  /**
   * The core subpackages of {@code indexing}, lowest first. A core package depends only on itself
   * and on core packages before it; {@code indexing} itself (the wiring), its web package and the
   * connectors sit above the whole core.
   */
  static final List<String> INDEXING_CORE =
      List.of(
          "indexing.chunk",
          "indexing.job",
          "indexing.attachment",
          "indexing.document",
          "indexing.source",
          "indexing.maintenance");

  /** Every direct subpackage of this one, relative to the root, is a connector. */
  static final String CONNECTOR_PARENT = "indexing.source";

  /** Packages of the separate {@code opaa-api} Gradle module: a library below all layers. */
  static final List<String> OUTSIDE_THE_LAYERING = List.of("api.dto", "api.types");

  /**
   * The web layer of a top-level package lives in its direct subpackage of this name ({@code
   * space.web}): its controllers, their response mappers and web helpers. A deeper package of that
   * name, such as the connector {@code indexing.source.web}, is none.
   */
  static final String WEB = "web";

  /** The package of the endpoints and web helpers every module shares. */
  static final String API = "api";

  /** The classes {@link #API} holds: what every module shares. */
  static final Set<String> SHARED_API_CLASSES =
      Set.of(
          "ErrorBodyNegotiator",
          "ErrorSanitizer",
          "GlobalExceptionHandler",
          "HealthController",
          "HttpClientConfig",
          "RequestLoggingFilter");

  private final String root;
  private final Set<String> knownCycleEdges;
  private final Set<String> apiClasses;

  public ModularArchitecture(String root) {
    this(root, Set.of());
  }

  /**
   * Rules over {@code root} that let the subpackage cycle edges in {@code knownCycleEdges} pass.
   */
  ModularArchitecture(String root, Set<String> knownCycleEdges) {
    this(root, knownCycleEdges, SHARED_API_CLASSES);
  }

  /**
   * Rules over {@code root} that let the subpackage cycle edges in {@code knownCycleEdges} pass and
   * allow exactly the classes named in {@code apiClasses} in {@link #API}.
   */
  ModularArchitecture(String root, Set<String> knownCycleEdges, Set<String> apiClasses) {
    this.root = root;
    this.knownCycleEdges = knownCycleEdges;
    this.apiClasses = apiClasses;
  }

  ArchRule everyPackageIsAssigned() {
    return classes()
        .that(areInTheRoot())
        .should(beAssignedToALayerAndAModule())
        .allowEmptyShould(true);
  }

  /**
   * A web package is exempt: it sits above every package its module may reach, so the module rule
   * bounds it, and {@link #onlyTheWebLayerAndAppDependOnAWebPackage} keeps the edges into it.
   */
  ArchRule noPackageDependsOnAHigherLayer() {
    return classes()
        .that(areInTheRoot())
        .should(
            dependOnly(
                "depend on no higher layer",
                (origin, target) -> {
                  int from = isInAWebPackage(origin) ? -1 : indexOf(layerOf(origin));
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

  /** Each web package forms a slice of its own, apart from its top-level package. */
  ArchRule topLevelPackagesAreFreeOfCycles() {
    return slices()
        .assignedFrom(
            new SliceAssignment() {
              @Override
              public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
                String relative = relative(javaClass.getPackageName());
                if (relative == null || relative.isEmpty()) {
                  return SliceIdentifier.ignore();
                }
                return SliceIdentifier.of(
                    isWebPackage(relative) ? topLevel(relative) + "." + WEB : topLevel(relative));
              }

              @Override
              public String getDescription() {
                return "top-level packages of " + root + ", each web package apart";
              }
            })
        .should()
        .beFreeOfCycles()
        .allowEmptyShould(true);
  }

  /**
   * Controllers and response mappers live in the web package of a top-level package, or in {@link
   * #API} as far as {@link #apiHoldsOnlyItsListedClasses} allows.
   */
  ArchRule webClassesResideInAWebPackage() {
    return classes()
        .that(areInTheRoot())
        .and(DescribedPredicate.describe("are controllers or response mappers", this::isWebClass))
        .should(
            new ArchCondition<>("reside in a web package or in " + root + "." + API) {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                String relative = relative(javaClass.getPackageName());
                if (!isWebPackage(relative) && !API.equals(relative)) {
                  events.add(
                      SimpleConditionEvent.violated(
                          javaClass,
                          javaClass.getName()
                              + " is a web class outside a web package: move it to the package"
                              + " <top-level package>."
                              + WEB
                              + " of its module"));
                }
              }
            })
        .allowEmptyShould(true);
  }

  ArchRule apiHoldsOnlyItsListedClasses() {
    return classes()
        .that(
            DescribedPredicate.describe(
                "are in " + root + "." + API,
                javaClass -> API.equals(relative(javaClass.getPackageName()))))
        .should(
            new ArchCondition<>("be listed as shared by every module") {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                String name = outermostSimpleName(javaClass);
                if (!name.equals("package-info") && !apiClasses.contains(name)) {
                  events.add(
                      SimpleConditionEvent.violated(
                          javaClass,
                          javaClass.getName()
                              + " is not shared by every module: move it to the web package of"
                              + " its module"));
                }
              }
            })
        .allowEmptyShould(true);
  }

  ArchRule onlyTheWebLayerAndAppDependOnAWebPackage() {
    DescribedPredicate<JavaClass> webClass =
        DescribedPredicate.describe("are in a web package", this::isInAWebPackage);
    return noClasses()
        .that(areInTheRoot())
        .and(DescribedPredicate.not(webClass))
        .and(
            DescribedPredicate.describe(
                "are outside module " + APP, javaClass -> moduleOf(javaClass) != APP))
        .should()
        .dependOnClassesThat(webClass)
        .because("the web layer sits on top of its module; the domain never reaches back into it")
        .allowEmptyShould(true);
  }

  ArchRule subpackagesAreFreeOfCycles() {
    return classes()
        .that(areInTheRoot())
        .should(formNoSubpackageCycleBeyondTheKnownEdges())
        .allowEmptyShould(true);
  }

  /**
   * The edges {@code a -> b} between two packages below the same top-level package that lie on a
   * cycle, that is whose ends share a strongly connected component; each with one dependency as
   * evidence.
   */
  Map<String, Dependency> subpackageCycleEdges(Iterable<JavaClass> classes) {
    Map<String, Map<String, Dependency>> graph = new TreeMap<>();
    for (JavaClass origin : classes) {
      String from = relative(origin.getPackageName());
      if (from == null || from.isEmpty()) {
        continue;
      }
      for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
        String to = relative(dependency.getTargetClass().getBaseComponentType().getPackageName());
        if (to != null
            && !to.isEmpty()
            && !to.equals(from)
            && !isOutsideTheLayering(to)
            && topLevel(to).equals(topLevel(from))) {
          graph.computeIfAbsent(from, key -> new TreeMap<>()).putIfAbsent(to, dependency);
        }
      }
    }
    Map<String, Integer> component = StronglyConnectedComponents.of(graph);
    Map<String, Dependency> cyclic = new TreeMap<>();
    graph.forEach(
        (from, targets) ->
            targets.forEach(
                (to, dependency) -> {
                  if (component.get(from).equals(component.get(to))) {
                    cyclic.put(from + " -> " + to, dependency);
                  }
                }));
    return cyclic;
  }

  private ArchCondition<JavaClass> formNoSubpackageCycleBeyondTheKnownEdges() {
    return new ArchCondition<>("form no subpackage cycle beyond the known edges") {
      private final List<JavaClass> seen = new ArrayList<>();

      @Override
      public void check(JavaClass javaClass, ConditionEvents events) {
        seen.add(javaClass);
      }

      @Override
      public void finish(ConditionEvents events) {
        subpackageCycleEdges(seen)
            .forEach(
                (edge, dependency) -> {
                  if (!knownCycleEdges.contains(edge)) {
                    events.add(
                        SimpleConditionEvent.violated(
                            dependency,
                            edge
                                + " lies on a cycle between subpackages and is no known edge in"
                                + " ModularArchitecture.KNOWN_SUBPACKAGE_CYCLE_EDGES: "
                                + dependency.getDescription()));
                  }
                });
      }
    };
  }

  ArchRule theIndexingCoreDependsOnlyDownward() {
    return classes()
        .that(areInTheRoot())
        .should(
            dependOnly(
                "depend on no later package of ModularArchitecture.INDEXING_CORE",
                (origin, target) -> {
                  int from =
                      INDEXING_CORE.indexOf(
                          relative(origin.getBaseComponentType().getPackageName()));
                  String to = relative(target.getBaseComponentType().getPackageName());
                  if (from < 0
                      || to == null
                      || !(to.equals("indexing") || to.startsWith("indexing."))) {
                    return null;
                  }
                  int index = INDEXING_CORE.indexOf(to);
                  if (index >= 0 && index <= from) {
                    return null;
                  }
                  return INDEXING_CORE.get(from)
                      + " -> "
                      + to
                      + (index < 0
                          ? " leaves the indexing core for a package above it"
                          : " points upward in ModularArchitecture.INDEXING_CORE");
                }))
        .allowEmptyShould(true);
  }

  /**
   * A new package below {@code indexing} is ordered in {@link #INDEXING_CORE} or is a connector.
   */
  ArchRule everyIndexingPackageIsInTheCore() {
    return classes()
        .that(areInTheRoot())
        .should(
            new ArchCondition<>(
                "lie in the indexing core, its root, its web package or a connector") {
              private final Set<String> unlisted = new TreeSet<>();

              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                String relative = relative(javaClass.getPackageName());
                if (relative != null
                    && relative.startsWith("indexing.")
                    && !INDEXING_CORE.contains(relative)
                    && !isWebPackage(relative)
                    && !relative.startsWith(CONNECTOR_PARENT + ".")) {
                  unlisted.add(relative);
                }
              }

              @Override
              public void finish(ConditionEvents events) {
                unlisted.forEach(
                    name ->
                        events.add(
                            SimpleConditionEvent.violated(
                                name,
                                root
                                    + "."
                                    + name
                                    + " is not ordered in the indexing core: insert it into"
                                    + " ModularArchitecture.INDEXING_CORE above every core"
                                    + " package it uses")));
              }
            })
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
        theIndexingCoreDependsOnlyDownward(),
        everyIndexingPackageIsInTheCore(),
        connectorsDoNotKnowEachOther(),
        noOneOutsideAConnectorKnowsIt(),
        webClassesResideInAWebPackage(),
        apiHoldsOnlyItsListedClasses(),
        onlyTheWebLayerAndAppDependOnAWebPackage());
  }

  /** Whether {@code javaClass} lies in the web package of a top-level package. */
  boolean isInAWebPackage(JavaClass javaClass) {
    return isWebPackage(relative(javaClass.getBaseComponentType().getPackageName()));
  }

  /** A controller or a response mapper. */
  private boolean isWebClass(JavaClass javaClass) {
    return javaClass.isAnnotatedWith(Controller.class)
        || javaClass.isMetaAnnotatedWith(Controller.class)
        || javaClass.getSimpleName().endsWith("ResponseMapper");
  }

  /** {@code <top-level package>.web} or below it; {@code relative} may be {@code null}. */
  private static boolean isWebPackage(String relative) {
    if (relative == null) {
      return false;
    }
    String[] segments = relative.split("\\.");
    return segments.length >= 2 && segments[1].equals(WEB);
  }

  /** The simple name of the top-level class that declares {@code javaClass}. */
  private static String outermostSimpleName(JavaClass javaClass) {
    String name = javaClass.getName().substring(javaClass.getPackageName().length() + 1);
    int nested = name.indexOf('$');
    return nested < 0 ? name : name.substring(0, nested);
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
    return moduleOfPackage(javaClass.getBaseComponentType().getPackageName());
  }

  /** The module of {@code packageName}, or {@code null} outside the root or unassigned. */
  public Module moduleOfPackage(String packageName) {
    String relative = relative(packageName);
    if (relative == null || isOutsideTheLayering(relative)) {
      return null;
    }
    if (relative.startsWith(CONNECTOR_PARENT + ".")) {
      return CONNECTORS;
    }
    return MODULES.get(relative.isEmpty() ? ROOT : topLevel(relative));
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
