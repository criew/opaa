package io.opaa.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static io.opaa.architecture.ModularArchitecture.Module.APP;
import static io.opaa.architecture.ModularArchitecture.Module.ASSISTANT;
import static io.opaa.architecture.ModularArchitecture.Module.CONNECTIONS;
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
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
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
          "msgraph",
          "format",
          "knowledge",
          "space",
          "succession",
          "revision",
          "diagnosticaccess",
          "llm",
          "metadata",
          "indexing",
          "connection",
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
    CONNECTIONS,
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
          entry("msgraph", FOUNDATION),
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
          entry("connection", CONNECTIONS),
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
   * CONNECTIONS implements the core's port and is reached by LIBRARY alone (ADR-0041).
   */
  public static final Map<Module, Set<Module>> ALLOWED_MODULE_EDGES =
      Map.ofEntries(
          entry(FOUNDATION, EnumSet.noneOf(Module.class)),
          entry(FORMAT, EnumSet.of(FOUNDATION)),
          entry(IDENTITY, EnumSet.of(FOUNDATION)),
          entry(RIGHTS, EnumSet.of(FOUNDATION, IDENTITY)),
          entry(KNOWLEDGE, EnumSet.of(FOUNDATION, FORMAT, IDENTITY, RIGHTS)),
          entry(CONNECTORS, EnumSet.of(FOUNDATION, FORMAT, KNOWLEDGE)),
          entry(CONNECTIONS, EnumSet.of(FOUNDATION, IDENTITY, RIGHTS, KNOWLEDGE)),
          entry(WORKSPACE, EnumSet.of(FOUNDATION, IDENTITY, RIGHTS, KNOWLEDGE)),
          entry(LIBRARY, EnumSet.of(FOUNDATION, FORMAT, IDENTITY, RIGHTS, KNOWLEDGE, CONNECTIONS)),
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
          "indexing.maintenance",
          "indexing.filesync");

  /** The root package of the module connections: it wires and implements the core's port. */
  static final String CONNECTION = "connection";

  /**
   * The subpackages of {@link #CONNECTION}, lowest first (ADR-0041, Entscheidung 8). One depends
   * only on itself and on the ones before it; the root package and its web package sit above all.
   */
  static final List<String> CONNECTION_PACKAGES =
      List.of(
          "connection.log",
          "connection.token",
          "connection.profile",
          "connection.request",
          "connection.consent",
          "connection.account",
          "connection.oauth");

  /**
   * The capability granted per connector type or profile, relative to the root (ADR-0036, Nachtrag
   * of 03.10.2026): only rights, which stores the scope, and connections, which decides it, name
   * it.
   */
  static final String CONNECTOR_RELEASE = "api.types.Capability#CREATE_CONNECTOR_LIBRARY";

  /**
   * The capability service, relative to the root: its methods that take a scope ({@code String})
   * and {@code #scopesOf} belong to the same two modules as {@link #CONNECTOR_RELEASE}.
   */
  static final String CAPABILITY_SERVICE = "permission.CapabilityService";

  /**
   * The core's port that hands out targets and secrets, relative to the root. Only the modules that
   * run, manage or answer a source hold it; everything else asks the read-only {@code
   * SourceStateLookup}.
   */
  static final String SECRET_PORT = "indexing.source.SourceConnectionResolver";

  /** Where "Sicht als" runs, relative to the root: the one foreign rights context. */
  static final String FOREIGN_CONTEXT = "diagnosticaccess";

  /** The vetted foreign context, relative to the root; a code unit holding it runs in it. */
  static final String FOREIGN_CONTEXT_TYPE = "diagnosticaccess.ForeignDiagnosticContext";

  /**
   * A person's own rights formula, relative to the root, in every shape. It includes their
   * owner-only libraries, so a foreign context asks {@code readableLibraryIdsInForeignContext}.
   */
  static final Set<String> OWN_FORMULA =
      Set.of(
          "knowledge.LibraryAccessService#readableLibraryIds",
          "permission.AssetAccessService#readableAssetIds",
          "permission.AssetAccessService#readableAssets",
          "permission.AssetAccessService#effectiveRoles");

  /** The modules that may hold {@link #SECRET_PORT}. */
  static final Set<Module> SECRET_PORT_HOLDERS = EnumSet.of(KNOWLEDGE, LIBRARY, CONNECTIONS);

  /**
   * The connector's change hooks, relative to the root: only {@link #CHANGE_GATE} calls them, so a
   * change validates against and compares the effective configuration everywhere.
   */
  static final String SOURCE_CONNECTOR = "indexing.source.SourceConnector";

  static final Set<String> CHANGE_HOOKS =
      Set.of("validateChange", "applyChange", "onSourceChanged");

  static final String CHANGE_GATE = "indexing.source.SourceChangeGate";

  /**
   * The library's own secret, relative to the root. Besides the core (module knowledge) only {@link
   * #SECRET_STORE} reads it; everyone else asks the port.
   */
  static final String LIBRARY_SECRET = "knowledge.KnowledgeLibrary#getSourceCredentials";

  static final String SECRET_STORE = "connection.token.ConnectionSecrets";

  /** The package of persons' connected accounts, relative to the root. */
  static final String CONNECTED_ACCOUNTS = "connection.account";

  /**
   * The port of the exact counts of persons' connections, relative to the root, and its methods
   * that answer them: only {@link #PERSON_NUMBERS} asks them, on the port or an implementation, and
   * hands out masked numbers.
   */
  static final String RAW_PERSON_COUNTS_PORT = "connection.profile.PersonConnections";

  static final Set<String> RAW_PERSON_COUNTS = Set.of("countsAmong");

  static final String PERSON_NUMBERS = "connection.profile.PersonNumbers";

  /**
   * The finders that enumerate the libraries of an organization or of the installation with the
   * private ones, or the private ones alone, relative to the root; the views of the administration
   * take {@code findSharedByOrganizationId} instead. Native SQL is not seen.
   */
  static final Set<String> UNFILTERED_LIBRARY_FINDERS =
      Set.of(
          "knowledge.KnowledgeLibraryRepository#findAll",
          "knowledge.KnowledgeLibraryRepository#findByOrganizationId",
          "knowledge.KnowledgeLibraryRepository#findIdsByOrganizationId",
          "knowledge.KnowledgeLibraryRepository#findByScheduleEnabledTrue",
          "knowledge.KnowledgeLibraryRepository#findByOrganizationIdAndExternalAccessState",
          "knowledge.KnowledgeLibraryRepository#findPrivateIdsByOrganizationId",
          "knowledge.KnowledgeLibraryRepository#findPrivateIdsByOwnerUserIdIn",
          "knowledge.KnowledgeLibraryRepository#findIdsByErasureRequested",
          "knowledge.KnowledgeLibraryRepository"
              + "#findByExternalAccessStateAndExternalAccessExpiresAtLessThanEqual",
          "knowledge.KnowledgeLibraryRepository"
              + "#findByExternalAccessStateAndExternalAccessReminderSentAtIsNullAnd"
              + "ExternalAccessExpiresAtBetween");

  /**
   * The system processes that may enumerate private libraries, relative to the root: each one shows
   * no library's name to anyone or leaves the private ones out itself.
   */
  static final Set<String> LIBRARY_ENUMERATORS =
      Set.of(
          "indexing.maintenance.PipelineReindexService",
          "indexing.source.LibraryIndexingScheduler",
          "library.OrphanedOriginalCleanupService",
          "library.LibraryExternalAccessService",
          "library.LibraryExternalAccessExpiryService",
          "library.LibraryExternalAccessReminderService",
          "library.PrivateLibraryDeletionRun");

  /**
   * The reads of one person's use across her private libraries, relative to the root, each with the
   * classes that may call it: the person's own view and the enforcement, the raw sum only the quota
   * itself, and the enforcement's verdict and message - which tell the use - only the intake.
   */
  static final Map<String, Set<String>> PERSONAL_USAGE_READERS =
      Map.of(
          "knowledge.PersonalStorageQuota#usageOf",
          Set.of("knowledge.LibraryStorageQuotaService", "library.web.MyPrivateStorageController"),
          "knowledge.DocumentRepository#sumFileSizeOfPrivateLibrariesOwnedBy",
          Set.of("knowledge.PersonalStorageQuota"),
          "knowledge.LibraryStorageQuotaService#verdictFor",
          Set.of("indexing.document.DocumentIngestService"),
          "knowledge.LibraryStorageQuotaService#personalQuotaExceededMessage",
          Set.of("indexing.document.DocumentIngestService"));

  /**
   * Exact counts about persons that only go to the log, relative to the root, each with the one
   * class that may call it.
   */
  static final Map<String, String> LOG_ONLY_PERSON_COUNTS =
      Map.of(
          "connection.token.ConnectionSecrets#countExpiredPersonSecrets",
          "connection.account.ConnectionLifecycleReconciler");

  /**
   * The step that stores a person's connection, relative to the root: it trusts that the secret
   * signed in already, so only {@link #ESTABLISHING_PACKAGES} call it.
   */
  static final String ESTABLISHED = "connection.account.ConnectedAccountService#established";

  static final Set<String> ESTABLISHING_PACKAGES = Set.of("connection.account", "connection.oauth");

  /**
   * A profile's registration with its decrypted secret, relative to the root: only {@link
   * #SIGN_IN_PACKAGE} reads it or holds the value, so the secret and the key reach no other class.
   */
  static final String PROFILE_REGISTRATION =
      "connection.profile.ProfileRegistrations#registrationOf";

  static final String CLIENT_REGISTRATION = "connection.profile.ClientRegistration";

  static final String SIGN_IN_PACKAGE = "connection.oauth";

  /**
   * The one way of module CONNECTIONS to an authorization server, relative to the root: only it
   * calls {@link #FORM_POST}, so every token, code exchange and revocation passes its target check
   * and keeps secrets out of its logs.
   */
  static final String OAUTH_CLIENT = "connection.oauth.OAuthClient";

  static final String FORM_POST = "sourceaccess.SourceFormPost";

  /**
   * The profiles' repository, relative to the root, and its lists across every kind of profile: an
   * MCP server has no source type, so the connector paths list only through {@code
   * findConnectorsByName} and only {@link #MCP_SERVER_ADMINISTRATION} lists MCP servers.
   */
  static final String PROFILE_REPOSITORY = "connection.profile.ConnectionProfileRepository";

  static final Set<String> PROFILE_LISTS_OF_ANY_KIND =
      Set.of("findAll", "findByKindOrderByNameAsc");

  static final String MCP_SERVER_ADMINISTRATION = "connection.profile.McpServerProfileService";

  /**
   * The values that carry a refresh token, relative to the root as {@code package.Outer$Inner}:
   * their {@code refreshToken()} is read only in {@link #TOKEN_STORE_PACKAGES}, so a refresh token
   * travels only between the provider and the store.
   */
  static final Set<String> REFRESH_TOKEN_CARRIERS =
      Set.of(
          "connection.token.NewSecret$OAuthGrant",
          "connection.token.SecretIssuer$Issued",
          "connection.token.SecretIssuer$StoredTokens",
          "connection.oauth.OAuthClient$Grant");

  static final Set<String> TOKEN_STORE_PACKAGES = Set.of("connection.token", "connection.oauth");

  /** The web classes that serve a person their own connected accounts, relative to the root. */
  static final Set<String> OWN_ACCOUNT_WEB =
      Set.of(
          "connection.web.ConnectedAccountController",
          "connection.web.ConnectedAccountResponseMapper",
          "connection.web.ConnectionAuthorizationController");

  /**
   * What a connector declares about profiles, relative to the root. In module connections only
   * {@link #PROFILE_REQUIREMENTS} reads it, so the switch of the system administration applies
   * everywhere.
   */
  static final String PROFILE_SUPPORT = "indexing.source.ProfileDeclaration#support";

  static final String PROFILE_REQUIREMENTS = "connection.profile.ProfileRequirements";

  /**
   * The repository of the document rows, relative to the root. Only {@link #DOCUMENT_DELETERS} call
   * one of its {@code delete…} methods or a modifying query that deletes, so every deletion outside
   * a run passes the one place that notes the revisit for the folder memory of the file sync
   * (ADR-0040).
   */
  static final String DOCUMENT_REPOSITORY = "knowledge.DocumentRepository";

  /**
   * The document service notes the revisit, the library service and the erasure of a private
   * library delete a library together with its sync state, and the cleanup service removes inside a
   * run.
   */
  static final Set<String> DOCUMENT_DELETERS =
      Set.of(
          "library.LibraryDocumentService",
          "library.KnowledgeLibraryService",
          "library.PrivateLibraryErasure",
          "indexing.maintenance.StaleDocumentCleanupService");

  /** The cleanup service, relative to the root, whose public methods remove documents in a run. */
  static final String CLEANUP_SERVICE = "indexing.maintenance.StaleDocumentCleanupService";

  /** The port the run frame reconciles through, relative to the root. */
  static final String RECONCILER = "indexing.source.VanishedDocumentReconciler";

  /** The methods of {@link #CLEANUP_SERVICE} and {@link #RECONCILER} that remove documents. */
  static final Set<String> CLEANUP_METHODS =
      Set.of("reconcile", "cleanupVanished", "removeWithAttachments");

  /** The run frame and the run bodies that remove through the cleanup service. */
  static final Set<String> RUN_REMOVERS =
      Set.of(
          "indexing.source.IndexingRunTemplate",
          "indexing.filesync.FileSync",
          "indexing.source.confluence.ConfluenceIndexingExecutor");

  /** Every direct subpackage of this one, relative to the root, is a connector. */
  static final String CONNECTOR_PARENT = "indexing.source";

  /**
   * The reads of a library's source configuration, relative to the root, that a connector leaves to
   * the core: it takes target, secret and settings as {@code SourceSettings} or {@code
   * ConnectorData} (ADR-0041, Entscheidung 3a). {@code getSourcePath} and {@code getWebhookSecret}
   * stay readable.
   */
  static final Set<String> CORE_ONLY_SOURCE_READS =
      Set.of(
          "knowledge.KnowledgeLibrary#getSourceCredentials",
          "knowledge.KnowledgeLibrary#getSourceUrl",
          "knowledge.KnowledgeLibrary#getSourceProxy",
          "knowledge.KnowledgeLibrary#isSourceInsecureSsl",
          "knowledge.KnowledgeLibrary#getSourceSettings",
          "indexing.source.ConnectorData#storedIn");

  /**
   * The core's resolvers and its service account sign-in, relative to the root. A connector that
   * held one could resolve the secret of any library or read a service account key (ADR-0040,
   * Entscheidung 2), so it never depends on them.
   */
  static final Set<String> CORE_ONLY_RESOLVERS =
      Set.of(
          "indexing.source.SourceConnectionResolver",
          "indexing.source.LibrarySourceConnectionResolver",
          "indexing.source.ServiceAccountKey",
          "indexing.source.ServiceAccountTokens");

  /** The shared file sync (ADR-0040, Entscheidung 1): connectors use it, it knows none of them. */
  static final String FILE_SYNC = "indexing.filesync";

  /** The only packages outside the root the file sync may use: the JDK and logging. */
  static final List<String> FILE_SYNC_EXTERNALS = List.of("java.", "org.slf4j.");

  /**
   * The packages inside the root the file sync may use besides the indexing core: neutral ones
   * only, so a provider access package - present or future - is refused without being named.
   */
  static final List<String> FILE_SYNC_ALLOWED =
      List.of("common", "sourceaccess", "format", "knowledge", "api.types");

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
   * The subpackages of {@link #CONNECTION} depend only downward in {@link #CONNECTION_PACKAGES}; a
   * subpackage outside the list is reported, so none can name the root package unnoticed.
   */
  ArchRule theConnectionPackagesDependOnlyDownward() {
    return classes()
        .that(areInTheRoot())
        .should(
            new ArchCondition<>("depend only downward in ModularArchitecture.CONNECTION_PACKAGES") {
              private final Set<String> unlisted = new TreeSet<>();

              @Override
              public void check(JavaClass origin, ConditionEvents events) {
                String from = relative(origin.getBaseComponentType().getPackageName());
                if (from == null || !from.startsWith(CONNECTION + ".") || isWebPackage(from)) {
                  return;
                }
                int index = CONNECTION_PACKAGES.indexOf(from);
                if (index < 0) {
                  unlisted.add(from);
                  return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                  String to =
                      relative(dependency.getTargetClass().getBaseComponentType().getPackageName());
                  if (to == null || !(to.equals(CONNECTION) || to.startsWith(CONNECTION + "."))) {
                    continue;
                  }
                  int target = CONNECTION_PACKAGES.indexOf(to);
                  if (target < 0 || target > index) {
                    events.add(
                        SimpleConditionEvent.violated(
                            dependency,
                            from
                                + " -> "
                                + to
                                + (target < 0
                                    ? " leaves the connection subpackages for a package above them"
                                    : " points upward in ModularArchitecture.CONNECTION_PACKAGES")
                                + ": "
                                + dependency.getDescription()));
                  }
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
                                    + " is not ordered: insert it into"
                                    + " ModularArchitecture.CONNECTION_PACKAGES above every"
                                    + " package it uses")));
              }
            })
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

  /**
   * The file sync knows no provider: it uses only the JDK, logging, the indexing core and the
   * neutral packages of {@link #FILE_SYNC_ALLOWED} - no connector, no provider access, no client.
   */
  ArchRule theFileSyncKnowsNoProvider() {
    return classes()
        .that(
            DescribedPredicate.describe(
                "are in " + root + "." + FILE_SYNC,
                javaClass -> {
                  String relative = relative(javaClass.getPackageName());
                  return relative != null
                      && (relative.equals(FILE_SYNC) || relative.startsWith(FILE_SYNC + "."));
                }))
        .should(
            dependOnly(
                "depend only on the JDK, logging, the indexing core and neutral packages",
                (origin, target) -> {
                  JavaClass base = target.getBaseComponentType();
                  if (base.isPrimitive()) {
                    return null;
                  }
                  String name = base.getName();
                  String relative = relative(base.getPackageName());
                  if (relative == null) {
                    return FILE_SYNC_EXTERNALS.stream().anyMatch(name::startsWith)
                        ? null
                        : FILE_SYNC + " -> " + name + " is a third-party client";
                  }
                  boolean allowed =
                      INDEXING_CORE.contains(relative)
                          || FILE_SYNC_ALLOWED.stream()
                              .anyMatch(
                                  allowedPackage ->
                                      relative.equals(allowedPackage)
                                          || relative.startsWith(allowedPackage + "."));
                  return allowed
                      ? null
                      : FILE_SYNC + " -> " + relative + " is no package the file sync may use";
                }))
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

  /**
   * Calls and method references alike; the push secret and the filesystem path are exempt. A
   * connector - and the file sync it hands its store to - neither holds nor creates a resolver of
   * the core.
   */
  ArchRule connectorsTakeTheirSourceConfigurationFromTheCore() {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are connector or file sync classes",
                javaClass -> isConnector(javaClass) || isInTheFileSync(javaClass)))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "read a source configuration listed in ModularArchitecture.CORE_ONLY_SOURCE_READS",
                access ->
                    CORE_ONLY_SOURCE_READS.contains(
                        relative(access.getTargetOwner().getPackageName())
                            + "."
                            + access.getTargetOwner().getSimpleName()
                            + "#"
                            + access.getName())))
        .orShould()
        .dependOnClassesThat(
            DescribedPredicate.describe(
                "are listed in ModularArchitecture.CORE_ONLY_RESOLVERS",
                target -> {
                  String relative = relative(target.getBaseComponentType().getPackageName());
                  return CORE_ONLY_RESOLVERS.contains(
                      relative + "." + target.getBaseComponentType().getSimpleName());
                }))
        .because(
            "a connector takes target, secret and settings from the core, which resolves them"
                + " (ADR-0041, Entscheidung 3a)")
        .allowEmptyShould(true);
  }

  /**
   * Field reads of {@link #CONNECTOR_RELEASE} and calls of the scoped methods of {@link
   * #CAPABILITY_SERVICE}, outside rights and connections. Classes outside every module ({@code
   * opaa-api}) are exempt.
   */
  ArchRule theConnectorReleaseIsDecidedInConnections() {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are outside the modules RIGHTS and CONNECTIONS",
                javaClass -> {
                  Module module = moduleOf(javaClass);
                  return module != null && module != RIGHTS && module != CONNECTIONS;
                }))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "read " + CONNECTOR_RELEASE + " or call a scoped method of " + CAPABILITY_SERVICE,
                access -> {
                  String owner =
                      relative(access.getTargetOwner().getPackageName())
                          + "."
                          + access.getTargetOwner().getSimpleName();
                  if ((owner + "#" + access.getName()).equals(CONNECTOR_RELEASE)) {
                    return true;
                  }
                  if (!owner.equals(CAPABILITY_SERVICE)
                      || !(access.getTarget() instanceof CodeUnitAccessTarget target)) {
                    return false;
                  }
                  return target.getName().equals("scopesOf")
                      || target.getRawParameterTypes().stream()
                          .anyMatch(type -> type.getName().equals(String.class.getName()));
                }))
        .because(
            "the scope of CREATE_CONNECTOR_LIBRARY is stored in rights and decided in connections;"
                + " every other module asks ConnectorReleaseService (ADR-0041, Entscheidung 8)")
        .allowEmptyShould(true);
  }

  /**
   * Classes outside {@link #SECRET_PORT_HOLDERS} depend on {@link #SECRET_PORT}. Connectors are
   * covered by {@link #connectorsTakeTheirSourceConfigurationFromTheCore}; the answer path asks
   * whether a source is updated through a port that cannot hand out a secret.
   */
  ArchRule theSecretPortStaysWithTheCore() {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are outside the modules " + SECRET_PORT_HOLDERS,
                javaClass -> {
                  Module module = moduleOf(javaClass);
                  return module != null
                      && module != CONNECTORS
                      && !SECRET_PORT_HOLDERS.contains(module);
                }))
        .should()
        .dependOnClassesThat(
            DescribedPredicate.describe(
                "are " + SECRET_PORT,
                target -> {
                  String relative = relative(target.getBaseComponentType().getPackageName());
                  return (relative + "." + target.getBaseComponentType().getSimpleName())
                      .equals(SECRET_PORT);
                }))
        .because(
            "only the core, the library administration and connections reach targets and secrets"
                + " (ADR-0041, Entscheidung 3)")
        .allowEmptyShould(true);
  }

  /**
   * Reaches of {@link #OWN_FORMULA} from a foreign context: any code unit of {@link
   * #FOREIGN_CONTEXT} or below it, and any code unit elsewhere that takes or reads a {@link
   * #FOREIGN_CONTEXT_TYPE}. ArchUnit counts a lambda's accesses to its enclosing method.
   */
  ArchRule theForeignContextNeverUsesTheOwnFormula() {
    return classes()
        .should(
            new ArchCondition<JavaClass>("not reach the own rights formula in a foreign context") {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                String relative = relative(javaClass.getBaseComponentType().getPackageName());
                if (relative == null) {
                  return;
                }
                boolean foreignPackage =
                    relative.equals(FOREIGN_CONTEXT) || relative.startsWith(FOREIGN_CONTEXT + ".");
                for (JavaCodeUnit codeUnit : javaClass.getCodeUnits()) {
                  boolean foreign =
                      foreignPackage
                          || codeUnit.getRawParameterTypes().stream()
                              .anyMatch(type -> FOREIGN_CONTEXT_TYPE.equals(relativeName(type)))
                          || codeUnit.getAccessesFromSelf().stream()
                              .anyMatch(
                                  access ->
                                      FOREIGN_CONTEXT_TYPE.equals(
                                          relativeName(access.getTargetOwner())));
                  if (!foreign) {
                    continue;
                  }
                  codeUnit.getAccessesFromSelf().stream()
                      .filter(
                          access ->
                              OWN_FORMULA.contains(
                                  relativeName(access.getTargetOwner()) + "#" + access.getName()))
                      .forEach(
                          access ->
                              events.add(
                                  SimpleConditionEvent.violated(access, access.getDescription())));
                }
              }
            })
        .because(
            "an owner-only library never enters a foreign rights context (ADR-0036, Nachtrag 4)")
        .allowEmptyShould(true);
  }

  /**
   * Calls of {@link #CHANGE_HOOKS} on {@link #SOURCE_CONNECTOR} or an implementation, outside
   * {@link #CHANGE_GATE}.
   */
  ArchRule onlyTheChangeGateCallsTheConnectorChangeHooks() {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are not " + CHANGE_GATE,
                javaClass -> !CHANGE_GATE.equals(relativeName(javaClass))))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "call a change hook of " + SOURCE_CONNECTOR,
                access ->
                    CHANGE_HOOKS.contains(access.getName())
                        && (SOURCE_CONNECTOR.equals(relativeName(access.getTargetOwner()))
                            || access.getTargetOwner().getAllRawInterfaces().stream()
                                .anyMatch(type -> SOURCE_CONNECTOR.equals(relativeName(type))))))
        .because(
            "a change reaches the connector only through the gate, which hands it the effective"
                + " configuration before and after")
        .allowEmptyShould(true);
  }

  /** Reads of {@link #LIBRARY_SECRET} outside module knowledge and {@link #SECRET_STORE}. */
  ArchRule theLibrarySecretIsReadInOnePlace() {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are outside module KNOWLEDGE and not " + SECRET_STORE,
                javaClass -> {
                  Module module = moduleOf(javaClass);
                  return module != null
                      && module != KNOWLEDGE
                      && !SECRET_STORE.equals(relativeName(javaClass));
                }))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "read " + LIBRARY_SECRET,
                access ->
                    LIBRARY_SECRET.equals(
                        relativeName(access.getTargetOwner()) + "#" + access.getName())))
        .because(
            "the secret has one store; the library administration asks the port, connections the"
                + " store")
        .allowEmptyShould(true);
  }

  /**
   * Dependencies on {@link #SECRET_STORE} outside module CONNECTIONS: the library administration
   * reaches the secret only through the port.
   */
  ArchRule theSecretStoreIsUsedOnlyInConnections() {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are outside module CONNECTIONS",
                javaClass -> {
                  Module module = moduleOf(javaClass);
                  return module != null && module != CONNECTIONS;
                }))
        .should()
        .dependOnClassesThat(
            DescribedPredicate.describe(
                "are " + SECRET_STORE,
                target -> SECRET_STORE.equals(relativeName(target.getBaseComponentType()))))
        .because("only connections reads the secret store; everyone else asks the port")
        .allowEmptyShould(true);
  }

  /**
   * Dependencies of a web class - a controller, a response mapper or a web helper of any module -
   * on {@link #CONNECTED_ACCOUNTS}; only {@link #OWN_ACCOUNT_WEB}, which serves a person their own
   * accounts, may. The administration gets numbers through {@link #PERSON_NUMBERS} only.
   */
  ArchRule theAdministrationNeverSeesAConnectedPerson() {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are web classes outside ModularArchitecture.OWN_ACCOUNT_WEB",
                javaClass -> {
                  String relative = relative(javaClass.getBaseComponentType().getPackageName());
                  if (relative == null || !isWebPackage(relative)) {
                    return false;
                  }
                  return !OWN_ACCOUNT_WEB.contains(
                      relative + "." + outermostSimpleName(javaClass.getBaseComponentType()));
                }))
        .should()
        .dependOnClassesThat(
            DescribedPredicate.describe(
                "are in " + CONNECTED_ACCOUNTS,
                target ->
                    CONNECTED_ACCOUNTS.equals(
                        relative(target.getBaseComponentType().getPackageName()))))
        .because(
            "the administration sees connected accounts only as masked numbers, never a person"
                + " or an account (ADR-0041, Beschluss 8)")
        .allowEmptyShould(true);
  }

  /**
   * Calls of {@link #RAW_PERSON_COUNTS}, on the port or an implementation, outside {@link
   * #PERSON_NUMBERS}, and of a {@link #LOG_ONLY_PERSON_COUNTS} method outside its one logging
   * caller: an exact count passed through any other class could reach an answer unmasked.
   */
  ArchRule personNumbersLeaveOnlyMasked() {
    String port = RAW_PERSON_COUNTS_PORT;
    return noClasses()
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "call "
                    + port
                    + RAW_PERSON_COUNTS
                    + " outside "
                    + PERSON_NUMBERS
                    + " or one of "
                    + LOG_ONLY_PERSON_COUNTS.keySet()
                    + " outside its logging caller",
                access -> {
                  String origin = relativeName(access.getOriginOwner().getBaseComponentType());
                  boolean raw =
                      RAW_PERSON_COUNTS.contains(access.getName())
                          && (port.equals(relativeName(access.getTargetOwner()))
                              || access.getTargetOwner().getAllRawInterfaces().stream()
                                  .anyMatch(type -> port.equals(relativeName(type))));
                  String logOnlyCaller =
                      LOG_ONLY_PERSON_COUNTS.get(
                          relativeName(access.getTargetOwner()) + "#" + access.getName());
                  return raw && !PERSON_NUMBERS.equals(origin)
                      || logOnlyCaller != null && !logOnlyCaller.equals(origin);
                }))
        .because(
            "a number about persons reaches the administration only masked below the minimum group"
                + " size (ADR-0036)")
        .allowEmptyShould(true);
  }

  /**
   * Protocol entries about an asset name it only neutrally: a code unit that builds an audit entry
   * ({@code audit.AuditEvent.Builder#object}) and reads {@code Asset#getName} names the asset by
   * {@code Asset#auditName}, and one that names an asset by {@code Asset#auditName} and writes a
   * payload ({@code before}/{@code after}) passes it through {@code Asset#auditPayload}, so an
   * owner-only asset carries no name, path or value.
   */
  ArchRule privateAssetsAreAuditedNeutrally() {
    String builder = root + ".audit.AuditEvent$Builder";
    String asset = root + ".asset.Asset";
    return classes()
        .should(
            new ArchCondition<JavaClass>("name an asset in a protocol entry only neutrally") {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaCodeUnit codeUnit : javaClass.getCodeUnits()) {
                  boolean buildsEntry = false;
                  boolean writesPayload = false;
                  boolean namesNeutrally = false;
                  boolean neutralizesPayload = false;
                  List<com.tngtech.archunit.core.domain.JavaAccess<?>> names = new ArrayList<>();
                  for (var access : codeUnit.getAccessesFromSelf()) {
                    JavaClass owner = access.getTargetOwner();
                    String name = access.getName();
                    if (owner.getName().equals(builder)) {
                      buildsEntry |= name.equals("object");
                      writesPayload |= name.equals("before") || name.equals("after");
                    } else if (owner.isAssignableTo(asset)) {
                      if (name.equals("getName")) {
                        names.add(access);
                      }
                      namesNeutrally |= name.equals("auditName");
                      neutralizesPayload |= name.equals("auditPayload");
                    }
                  }
                  if (buildsEntry && !namesNeutrally) {
                    names.forEach(
                        access ->
                            events.add(
                                SimpleConditionEvent.violated(access, access.getDescription())));
                  }
                  if (namesNeutrally && writesPayload && !neutralizesPayload) {
                    events.add(
                        SimpleConditionEvent.violated(
                            codeUnit,
                            codeUnit.getFullName()
                                + " writes a payload about an asset without auditPayload"));
                  }
                }
              }
            })
        .because(
            "a protocol entry about a private library names neither it nor its content"
                + " (ADR-0041, Entscheidung 6)")
        .allowEmptyShould(true);
  }

  /**
   * Calls of a {@link #PERSONAL_USAGE_READERS} method outside its listed callers: no path of the
   * administration reads the use of one person.
   */
  ArchRule personalUsageIsReadOnlyByItsOwner() {
    return noClasses()
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "read a person's private storage use outside " + PERSONAL_USAGE_READERS,
                access -> {
                  Set<String> readers =
                      PERSONAL_USAGE_READERS.get(
                          relativeName(access.getTargetOwner()) + "#" + access.getName());
                  return readers != null
                      && !readers.contains(relativeName(topLevel(access.getOriginOwner())));
                }))
        .because(
            "the use of private libraries reaches the administration only as masked sums (#2166)")
        .allowEmptyShould(true);
  }

  /**
   * Calls of {@link #UNFILTERED_LIBRARY_FINDERS} outside {@link #LIBRARY_ENUMERATORS}: a private
   * library is its owner's alone and appears in no list of the administration, not even by name.
   */
  ArchRule privateLibrariesAreNotEnumeratedOutsideListedClasses() {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are not among " + LIBRARY_ENUMERATORS,
                javaClass -> {
                  String name = relativeName(topLevel(javaClass));
                  return name != null && !LIBRARY_ENUMERATORS.contains(name);
                }))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "call one of " + UNFILTERED_LIBRARY_FINDERS,
                access ->
                    UNFILTERED_LIBRARY_FINDERS.contains(
                        relativeName(access.getTargetOwner()) + "#" + access.getName())))
        .because(
            "the administration sees private libraries only summed up (ADR-0041, Entscheidung 6);"
                + " its views take findSharedByOrganizationId")
        .allowEmptyShould(true);
  }

  /**
   * Calls of {@link #PROFILE_REGISTRATION} and uses of {@link #CLIENT_REGISTRATION} outside {@link
   * #SIGN_IN_PACKAGE}, other than by the two classes that build the value.
   */
  ArchRule theProfileRegistrationLeavesOnlyToTheSignIn() {
    String owner = PROFILE_REGISTRATION.substring(0, PROFILE_REGISTRATION.indexOf('#'));
    String method = PROFILE_REGISTRATION.substring(PROFILE_REGISTRATION.indexOf('#') + 1);
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are outside " + SIGN_IN_PACKAGE + " and do not build the registration",
                javaClass -> {
                  JavaClass top = topLevel(javaClass.getBaseComponentType());
                  String relative = relative(top.getPackageName());
                  String name = relativeName(top);
                  return relative != null
                      && !SIGN_IN_PACKAGE.equals(relative)
                      && !owner.equals(name)
                      && !CLIENT_REGISTRATION.equals(name);
                }))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "call " + PROFILE_REGISTRATION,
                access ->
                    access.getName().equals(method)
                        && owner.equals(relativeName(access.getTargetOwner()))))
        .orShould()
        .dependOnClassesThat(
            DescribedPredicate.describe(
                "are " + CLIENT_REGISTRATION,
                javaClass -> CLIENT_REGISTRATION.equals(relativeName(javaClass))))
        .because(
            "it carries the profile's client secret or key; only the sign-in uses it, and hands out"
                + " the access token alone")
        .allowEmptyShould(true);
  }

  /**
   * Calls of {@link #PROFILE_LISTS_OF_ANY_KIND} on {@link #PROFILE_REPOSITORY} outside the
   * repository itself and {@link #MCP_SERVER_ADMINISTRATION}.
   */
  ArchRule connectorPathsSeeOnlyConnectorProfiles() {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are not " + PROFILE_REPOSITORY + " or " + MCP_SERVER_ADMINISTRATION,
                javaClass -> {
                  String name = relativeName(topLevel(javaClass.getBaseComponentType()));
                  return name != null
                      && !PROFILE_REPOSITORY.equals(name)
                      && !MCP_SERVER_ADMINISTRATION.equals(name);
                }))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "list " + PROFILE_REPOSITORY + " across every kind",
                access ->
                    PROFILE_LISTS_OF_ANY_KIND.contains(access.getName())
                        && PROFILE_REPOSITORY.equals(relativeName(access.getTargetOwner()))))
        .because(
            "an MCP server profile has no source type and no connector; a connector path that"
                + " listed it would fail on it or show it while the kind stays hidden (ADR-0041,"
                + " Entscheidung 5)")
        .allowEmptyShould(true);
  }

  /** Calls of {@link #FORM_POST} in module CONNECTIONS outside {@link #OAUTH_CLIENT}. */
  ArchRule theAuthorizationServerIsReachedOnlyThroughTheOAuthClient() {
    String method = "post";
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are in module CONNECTIONS and not " + OAUTH_CLIENT,
                javaClass ->
                    moduleOf(javaClass) == CONNECTIONS
                        && !OAUTH_CLIENT.equals(relativeName(topLevel(javaClass)))))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "call " + FORM_POST + "#" + method,
                access ->
                    access.getName().equals(method)
                        && FORM_POST.equals(relativeName(access.getTargetOwner()))))
        .because(
            "a request to a token or revocation endpoint carries a secret; the OAuth client checks"
                + " its target, takes no redirect and logs none of it")
        .allowEmptyShould(true);
  }

  /**
   * Reads of {@code refreshToken()} on a {@link #REFRESH_TOKEN_CARRIERS} value outside {@link
   * #TOKEN_STORE_PACKAGES}.
   */
  ArchRule refreshTokensStayInTheTokenStore() {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are outside " + TOKEN_STORE_PACKAGES,
                javaClass -> {
                  String relative = relative(javaClass.getBaseComponentType().getPackageName());
                  return relative != null && !TOKEN_STORE_PACKAGES.contains(relative);
                }))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "read the refresh token of " + REFRESH_TOKEN_CARRIERS,
                access ->
                    access.getName().equals("refreshToken")
                        && REFRESH_TOKEN_CARRIERS.contains(nestedName(access.getTargetOwner()))))
        .because(
            "a refresh token goes from the provider into the store and back, never to another"
                + " class that could hand it out, log it or answer with it")
        .allowEmptyShould(true);
  }

  /**
   * {@code javaClass} relative to the root as {@code package.Outer$Inner}, {@code null} outside.
   */
  private String nestedName(JavaClass javaClass) {
    String relative = relative(javaClass.getPackageName());
    if (relative == null) {
      return null;
    }
    String name = javaClass.getName();
    return relative + "." + name.substring(name.lastIndexOf('.') + 1);
  }

  /** Calls of {@link #ESTABLISHED} outside {@link #ESTABLISHING_PACKAGES}. */
  ArchRule aConnectionIsEstablishedOnlyAfterItsSignIn() {
    String owner = ESTABLISHED.substring(0, ESTABLISHED.indexOf('#'));
    String method = ESTABLISHED.substring(ESTABLISHED.indexOf('#') + 1);
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are outside " + ESTABLISHING_PACKAGES,
                javaClass -> {
                  String relative = relative(javaClass.getBaseComponentType().getPackageName());
                  return relative != null && !ESTABLISHING_PACKAGES.contains(relative);
                }))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "call " + ESTABLISHED,
                access ->
                    access.getName().equals(method)
                        && owner.equals(relativeName(access.getTargetOwner()))))
        .because(
            "it stores a person's secret without signing in; every other path connects through the"
                + " sign-in first")
        .allowEmptyShould(true);
  }

  /**
   * Reads of {@link #PROFILE_SUPPORT} in module CONNECTIONS outside {@link #PROFILE_REQUIREMENTS}.
   */
  ArchRule theProfileSupportIsReadInOnePlace() {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are in module CONNECTIONS and not " + PROFILE_REQUIREMENTS,
                javaClass ->
                    moduleOf(javaClass) == CONNECTIONS
                        && !PROFILE_REQUIREMENTS.equals(relativeName(javaClass))))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "read " + PROFILE_SUPPORT,
                access ->
                    PROFILE_SUPPORT.equals(
                        relativeName(access.getTargetOwner()) + "#" + access.getName())))
        .because(
            "the declared support is made REQUIRED by the system administration's switch in one"
                + " place; connections asks it for the effective support")
        .allowEmptyShould(true);
  }

  /**
   * Calls of a {@code delete…} method of {@link #DOCUMENT_REPOSITORY}, or of one whose modifying
   * query deletes, also by method reference, outside {@link #DOCUMENT_DELETERS} and the classes
   * nested in them.
   */
  ArchRule onlyTheKnownClassesDeleteDocuments() {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are not one of " + DOCUMENT_DELETERS,
                javaClass -> !DOCUMENT_DELETERS.contains(relativeName(topLevel(javaClass)))))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "call a delete method of " + DOCUMENT_REPOSITORY,
                access ->
                    DOCUMENT_REPOSITORY.equals(relativeName(access.getTargetOwner()))
                        && (access.getName().startsWith("delete")
                            || (access.getTarget() instanceof CodeUnitAccessTarget target
                                && target.resolveMember().filter(this::deletesRows).isPresent()))))
        .because(
            "a document deleted outside a run leaves a revisit, or the folder memory keeps the"
                + " folder that would bring it back")
        .allowEmptyShould(true);
  }

  /** A repository method annotated as modifying whose query deletes. */
  private boolean deletesRows(JavaCodeUnit method) {
    return method.isAnnotatedWith("org.springframework.data.jpa.repository.Modifying")
        && method
            .tryGetAnnotationOfType("org.springframework.data.jpa.repository.Query")
            .flatMap(query -> query.get("value"))
            .map(value -> value.toString().stripLeading().toLowerCase(java.util.Locale.ROOT))
            .filter(query -> query.startsWith("delete"))
            .isPresent();
  }

  /**
   * Calls of a removing method of {@link #CLEANUP_SERVICE} or {@link #RECONCILER}, also by method
   * reference, outside {@link #RUN_REMOVERS}, the classes nested in them and the service itself.
   */
  ArchRule onlyTheRunRemovesThroughTheCleanupService() {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are not one of " + RUN_REMOVERS,
                javaClass -> {
                  String name = relativeName(topLevel(javaClass));
                  return !RUN_REMOVERS.contains(name) && !CLEANUP_SERVICE.equals(name);
                }))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "call a removing method of " + CLEANUP_SERVICE,
                access -> {
                  String owner = relativeName(access.getTargetOwner());
                  return (CLEANUP_SERVICE.equals(owner) || RECONCILER.equals(owner))
                      && CLEANUP_METHODS.contains(access.getName());
                }))
        .because(
            "only a run carries the invariants of a removal - a complete listing for the"
                + " reconciliation, a positive finding for a single document - and a deletion"
                + " outside it leaves no revisit")
        .allowEmptyShould(true);
  }

  private static JavaClass topLevel(JavaClass javaClass) {
    JavaClass top = javaClass;
    while (top.getEnclosingClass().isPresent()) {
      top = top.getEnclosingClass().get();
    }
    return top;
  }

  /** {@code javaClass} relative to the root, {@code null} outside it. */
  private String relativeName(JavaClass javaClass) {
    String relative = relative(javaClass.getPackageName());
    return relative == null ? null : relative + "." + javaClass.getSimpleName();
  }

  List<ArchRule> all() {
    return List.of(
        onlyTheChangeGateCallsTheConnectorChangeHooks(),
        theLibrarySecretIsReadInOnePlace(),
        theSecretStoreIsUsedOnlyInConnections(),
        theAdministrationNeverSeesAConnectedPerson(),
        personNumbersLeaveOnlyMasked(),
        aConnectionIsEstablishedOnlyAfterItsSignIn(),
        theProfileRegistrationLeavesOnlyToTheSignIn(),
        theAuthorizationServerIsReachedOnlyThroughTheOAuthClient(),
        refreshTokensStayInTheTokenStore(),
        theForeignContextNeverUsesTheOwnFormula(),
        privateLibrariesAreNotEnumeratedOutsideListedClasses(),
        personalUsageIsReadOnlyByItsOwner(),
        privateAssetsAreAuditedNeutrally(),
        theProfileSupportIsReadInOnePlace(),
        onlyTheKnownClassesDeleteDocuments(),
        onlyTheRunRemovesThroughTheCleanupService(),
        theSecretPortStaysWithTheCore(),
        theConnectorReleaseIsDecidedInConnections(),
        everyPackageIsAssigned(),
        noPackageDependsOnAHigherLayer(),
        modulesDependOnlyOnAllowedModules(),
        topLevelPackagesAreFreeOfCycles(),
        subpackagesAreFreeOfCycles(),
        theIndexingCoreDependsOnlyDownward(),
        everyIndexingPackageIsInTheCore(),
        theConnectionPackagesDependOnlyDownward(),
        theFileSyncKnowsNoProvider(),
        connectorsDoNotKnowEachOther(),
        noOneOutsideAConnectorKnowsIt(),
        webClassesResideInAWebPackage(),
        apiHoldsOnlyItsListedClasses(),
        onlyTheWebLayerAndAppDependOnAWebPackage(),
        connectorsTakeTheirSourceConfigurationFromTheCore());
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

  private boolean isInTheFileSync(JavaClass javaClass) {
    String relative = relative(javaClass.getBaseComponentType().getPackageName());
    return relative != null && (relative.equals(FILE_SYNC) || relative.startsWith(FILE_SYNC + "."));
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
