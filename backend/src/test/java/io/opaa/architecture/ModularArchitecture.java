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
  public enum Module {
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
          entry("security", FOUNDATION),
          entry("ratelimit", FOUNDATION),
          entry("sourceaccess", FOUNDATION),
          entry("s3", FOUNDATION),
          entry("audit", IDENTITY),
          entry("branding", IDENTITY),
          entry("mail", IDENTITY),
          entry("auth", IDENTITY),
          entry("notification", IDENTITY),
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
   * EXTERNAL reaches RIGHTS only through members a library inherits from {@code
   * io.opaa.asset.Asset}: a method reference such as {@code KnowledgeLibrary::getId} names the
   * declaring class.
   */
  public static final Map<Module, Set<Module>> ALLOWED_MODULE_EDGES =
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
   * The package edges, relative to the root, that lie on a cycle between the subpackages of one
   * top-level package today. Any other edge on such a cycle - and so every new cycle - fails the
   * test; an edge that no longer lies on a cycle is removed from this list.
   */
  static final Set<String> KNOWN_SUBPACKAGE_CYCLE_EDGES =
      Set.of(
          "auth -> auth.local",
          "auth -> auth.oidc",
          "auth.local -> auth",
          "auth.local -> auth.oidc",
          "auth.oidc -> auth",
          "auth.oidc -> auth.local",
          "externalaccess -> externalaccess.token",
          "externalaccess.token -> externalaccess",
          "group -> group.sync",
          "group.sync -> group",
          "indexing -> indexing.attachment",
          "indexing -> indexing.chunk",
          "indexing -> indexing.document",
          "indexing -> indexing.format",
          "indexing -> indexing.format.file.fallback",
          "indexing -> indexing.format.file.html",
          "indexing -> indexing.format.file.mail",
          "indexing -> indexing.format.file.markdown",
          "indexing -> indexing.format.file.office",
          "indexing -> indexing.format.file.pdf",
          "indexing -> indexing.format.file.tabular",
          "indexing -> indexing.job",
          "indexing -> indexing.maintenance",
          "indexing -> indexing.metadata",
          "indexing -> indexing.source",
          "indexing.attachment -> indexing.document",
          "indexing.attachment -> indexing.format",
          "indexing.attachment -> indexing.job",
          "indexing.attachment -> indexing.source",
          "indexing.chunk -> indexing",
          "indexing.document -> indexing",
          "indexing.document -> indexing.attachment",
          "indexing.document -> indexing.chunk",
          "indexing.document -> indexing.format",
          "indexing.document -> indexing.job",
          "indexing.document -> indexing.metadata",
          "indexing.document -> indexing.source",
          "indexing.format -> indexing.metadata",
          "indexing.format.file.fallback -> indexing.chunk",
          "indexing.format.file.fallback -> indexing.document",
          "indexing.format.file.fallback -> indexing.format",
          "indexing.format.file.html -> indexing.format",
          "indexing.format.file.mail -> indexing.chunk",
          "indexing.format.file.mail -> indexing.format",
          "indexing.format.file.mail -> indexing.metadata",
          "indexing.format.file.markdown -> indexing.chunk",
          "indexing.format.file.markdown -> indexing.format",
          "indexing.format.file.office -> indexing.format",
          "indexing.format.file.pdf -> indexing.format",
          "indexing.format.file.tabular -> indexing.chunk",
          "indexing.format.file.tabular -> indexing.format",
          "indexing.format.file.tabular -> indexing.format.file.office",
          "indexing.job -> indexing",
          "indexing.job -> indexing.attachment",
          "indexing.job -> indexing.document",
          "indexing.job -> indexing.format",
          "indexing.job -> indexing.source",
          "indexing.maintenance -> indexing.attachment",
          "indexing.maintenance -> indexing.chunk",
          "indexing.maintenance -> indexing.document",
          "indexing.maintenance -> indexing.format",
          "indexing.maintenance -> indexing.job",
          "indexing.maintenance -> indexing.metadata",
          "indexing.maintenance -> indexing.source",
          "indexing.metadata -> indexing.chunk",
          "indexing.metadata -> indexing.format",
          "indexing.metadata -> indexing.maintenance",
          "indexing.source -> indexing.attachment",
          "indexing.source -> indexing.document",
          "indexing.source -> indexing.format",
          "indexing.source -> indexing.job",
          "indexing.source -> indexing.maintenance",
          "indexing.source.confluence -> indexing.source.confluence.webhook",
          "indexing.source.confluence.webhook -> indexing.source.confluence",
          "indexing.source.s3 -> indexing.source.s3.events",
          "indexing.source.s3.events -> indexing.source.s3",
          "query -> query.answer",
          "query -> query.citation",
          "query -> query.filter",
          "query -> query.retrieval",
          "query -> query.retrieval.ranking",
          "query -> query.retrieval.scope",
          "query -> query.retrieval.search",
          "query -> query.spike",
          "query.answer -> query",
          "query.answer -> query.citation",
          "query.citation -> query",
          "query.citation -> query.retrieval",
          "query.citation -> query.retrieval.scope",
          "query.filter -> query",
          "query.retrieval -> query",
          "query.retrieval.ranking -> query",
          "query.retrieval.ranking -> query.retrieval",
          "query.retrieval.scope -> query.retrieval",
          "query.retrieval.search -> query",
          "query.retrieval.search -> query.answer",
          "query.retrieval.search -> query.retrieval",
          "query.retrieval.search -> query.retrieval.scope",
          "query.spike -> query",
          "query.spike -> query.answer",
          "query.spike -> query.citation",
          "query.spike -> query.retrieval");

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

  /**
   * Classes still in {@link #API} that belong in the web package of their module; removed from here
   * as they move, until the list is empty (#2034).
   */
  static final Set<String> WEB_CLASSES_NOT_YET_MOVED =
      Set.of(
          "AvailablePromptController",
          "AvailablePromptResponseMapper",
          "ChatController",
          "ChatResponseMapper",
          "ChatSearchResponseMapper",
          "ExternalAccessSettingsController",
          "ExternalAccessSettingsResponseMapper",
          "ExternalAccessTokenAdminController",
          "ExternalAccessTokenController",
          "ExternalAccessTokenResponseMapper",
          "MetadataFilterDeserializer",
          "MetadataFilterMapper",
          "MetadataFilterOptionsResponseMapper",
          "PromptLibraryController",
          "PromptLibraryResponseMapper",
          "QueryController",
          "QueryResponseMapper",
          "SearchAdminController",
          "SearchAdminResponseMapper",
          "SearchController",
          "SearchResponseMapper");

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
