package io.opaa.indexing.source.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.connection.oauth.ProfileSignIn;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.indexing.credentials.ServiceAccountTokens;
import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.registry.SourceConnectorRegistry;
import io.opaa.indexing.registry.TestSourceConnectors;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.RequestBudget;
import io.opaa.indexing.spi.SourceBrowser;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceListing;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.Sleeper;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 3 against a real Microsoft 365 tenant, read-only (ADR-0047): runs only through {@code
 * ./gradlew sharePointIntegrationTest} on the maintainer's machine, the credentials in a file
 * outside the repository. The app registration holds {@code Sites.Selected} with {@code read} on
 * one site. Setup: {@code README.md} beside this class. An Entra outage shows as a failed sign-in,
 * not as a finding about OPAA.
 */
@EnabledIfEnvironmentVariable(named = "OPAA_SP_IT_ENV_FILE", matches = ".+")
class SharePointTenantIntegrationTest {

  private static final List<String> WATCHED =
      List.of(
          "io.opaa.indexing",
          "io.opaa.msgraph",
          "io.opaa.sourceaccess",
          "io.opaa.security",
          "io.opaa.connection");

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final GraphConnections graphs =
      new GraphConnections(
          SharePointProperties.defaults(),
          new TargetAddressValidator(true, List.of()),
          Sleeper.threadSleep());
  private final SharePointSourceConnector connector =
      new SharePointSourceConnector(
          SharePointSourceConnector.API_BASE,
          SharePointSourceConnector.TOKEN_TEMPLATE,
          graphs,
          mock(SourceSyncStateRepository.class));

  private Map<String, String> env;
  private String token;

  @BeforeEach
  void signIn() throws Exception {
    Path file = Path.of(System.getenv("OPAA_SP_IT_ENV_FILE")).toAbsolutePath().normalize();
    Path repository = Path.of("").toAbsolutePath().getParent();
    assertThat(file.startsWith(repository))
        .as("the credentials file lies outside the repository")
        .isFalse();
    env = read(file);
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    for (String name : WATCHED) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      logger.setLevel(Level.DEBUG);
      logger.addAppender(appender);
    }
    token = signInThroughAProfile().mint(PROFILE_ID).value();
    // every finding below holds only for an app without any tenant-wide right
    assertThat(roles(token)).as("app roles in the token").containsExactly("Sites.Selected");
  }

  @AfterEach
  void checkLogsAndDetach() {
    String logs =
        appender.list.stream()
            .map(
                event ->
                    event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                            ? ""
                            : event.getThrowableProxy().getMessage()))
            .collect(Collectors.joining("\n"));
    for (String name : WATCHED) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      logger.detachAppender(appender);
      logger.setLevel(null);
    }
    // booleans, so a leak never puts the secret itself into the report
    if (env != null) {
      assertThat(logs.contains(env.get("OPAA_SP_CLIENT_SECRET")))
          .as("client secret in the logs")
          .isFalse();
    }
    if (token != null) {
      assertThat(logs.contains(token)).as("access token in the logs").isFalse();
    }
  }

  @Test
  void theSharedSiteResolvesByItsAddressAndOffersItsDocumentLibraries() {
    String site = sharedSite();

    SourceListing libraries = browse(Map.of("site", site.substring("site:".length())));

    assertThat(libraries.complete()).as(libraries.message()).isTrue();
    assertThat(libraries.entries()).isNotEmpty();
    SourceListing folders =
        browse(Map.of("drive", drive(libraries.entries().getFirst()).driveId()));
    assertThat(folders.complete()).as(folders.message()).isTrue();
  }

  @Test
  void withSitesSelectedTheSearchNamesTheMissingPermissionInsteadOfFailing() {
    SourceListing search = browse(Map.of("search", "a"));

    assertThat(search.complete()).as("the search is refused under Sites.Selected").isFalse();
    assertThat(search.entries()).isEmpty();
    assertThat(search.message()).contains("Sites.Read.All");
  }

  @Test
  void aSiteNotSharedWithTheAppIsRefusedSoNoTenantWideRightIsNeeded() {
    String foreign = env.get("OPAA_SP_FOREIGN_SITE_URL");
    if (foreign == null || foreign.isBlank()) {
      // the tenant's root site, granted to no app with Sites.Selected unless done on purpose
      foreign = "https://" + URI.create(env.get("OPAA_SP_SITE_URL")).getHost() + "/";
    }

    SourceListing listing = browse(Map.of("siteUrl", foreign));

    assertThat(listing.complete()).as("a site outside the grant is not listed").isFalse();
    assertThat(listing.entries()).isEmpty();
    assertThat(listing.message()).contains("Sites.Selected");
  }

  @Test
  void theConnectionTestReachesEveryLibraryOfTheSharedSite() {
    SourceConnectionTestResult result = connector.testConnection(source(), null);

    assertThat(result.reachable()).as(result.message()).isTrue();
  }

  @Test
  void aFullSyncAndAChangeRunReadTheLibrariesCleanly() throws Exception {
    FileSyncHarness harness =
        new FileSyncHarness().maxFileSize(graphs.properties().maxFileSizeBytes());
    SharePointSettings settings = settings();

    FileSyncHarness.Run full = harness.fullSync(store(settings));

    assertThat(full.failure()).isNull();
    assertThat(full.listingComplete()).isTrue();
    assertThat(full.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage)
        .isEmpty();
    assertThat(harness.storedPaths()).isNotEmpty().allMatch(p -> p.startsWith("sharepoint://"));

    FileSyncHarness.Run changes = harness.changeRun(store(settings));

    assertThat(changes.failure()).isNull();
    assertThat(
            harness
                .state()
                .canReadChanges(
                    SharePointIndexingExecutor.streams(settings),
                    Duration.ofDays(1),
                    FileSyncHarness.NOW))
        .isTrue();
  }

  @Test
  void theOriginalOfEveryIndexedFileComesThroughTheDownloadRedirect() throws Exception {
    FileSyncHarness harness =
        new FileSyncHarness().maxFileSize(graphs.properties().maxFileSizeBytes());
    harness.fullSync(store(settings()));

    for (String path : harness.storedPaths()) {
      Document document = harness.stored(path).orElseThrow();
      Optional<DocumentContent> original =
          connector.openOriginal(document, mock(KnowledgeLibrary.class), source());
      assertThat(original).as(document.getFileName()).isPresent();
      try (InputStream in = original.get().stream()) {
        assertThat(in.readAllBytes()).as(document.getFileName()).isNotEmpty();
      }
    }
  }

  private static final UUID PROFILE_ID = UUID.randomUUID();

  /** The application roles the access token carries; role names are no secret. */
  private static List<String> roles(String accessToken) {
    String payload = accessToken.split("\\.")[1];
    JsonNode claims = JsonMapper.builder().build().readTree(Base64.getUrlDecoder().decode(payload));
    List<String> roles = new ArrayList<>();
    claims.path("roles").forEach(role -> roles.add(role.asString()));
    return roles;
  }

  private ProfileSignIn signInThroughAProfile() {
    Clock clock = Clock.systemUTC();
    ConnectionProfile profile =
        new ConnectionProfile(SharePointSourceConnector.TYPE, clock.instant());
    ReflectionTestUtils.setField(profile, "id", PROFILE_ID);
    ReflectionTestUtils.setField(profile, "name", "SharePoint-Mandantenlauf");
    ReflectionTestUtils.setField(
        profile, "serverUrl", SharePointSourceConnector.API_BASE.toString());
    ReflectionTestUtils.setField(profile, "authMethod", ConnectionAuthMethod.CLIENT_CREDENTIALS);
    ReflectionTestUtils.setField(profile, "ownership", ConnectionOwnership.LIBRARY);
    ReflectionTestUtils.setField(profile, "clientId", env.get("OPAA_SP_CLIENT_ID"));
    ReflectionTestUtils.setField(profile, "tenant", env.get("OPAA_SP_TENANT_ID"));
    ReflectionTestUtils.setField(profile, "clientSecretCiphertext", "enc:v1:tenant-run");
    CredentialsEncryptor encryptor = mock(CredentialsEncryptor.class);
    when(encryptor.decrypt("enc:v1:tenant-run")).thenReturn(env.get("OPAA_SP_CLIENT_SECRET"));
    ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
    when(profiles.findById(any())).thenReturn(Optional.of(profile));
    SourceConnectorRegistry registry = TestSourceConnectors.connectors().with(connector).registry();
    @SuppressWarnings("unchecked")
    ObjectProvider<SourceConnectorRegistry> connectors = mock(ObjectProvider.class);
    when(connectors.getObject()).thenReturn(registry);
    TargetAddressValidator validator = new TargetAddressValidator(true, List.of());
    return new ProfileSignIn(
        new ProfileRegistrations(
            profiles, encryptor, mock(PlatformTransactionManager.class), connectors, clock),
        new ServiceAccountTokens(validator, clock),
        validator,
        clock);
  }

  /** The {@code site:<id>} of {@code OPAA_SP_SITE_URL}. */
  private String sharedSite() {
    SourceListing site = browse(Map.of("siteUrl", env.get("OPAA_SP_SITE_URL")));
    assertThat(site.complete()).as(site.message()).isTrue();
    return site.entries().getFirst().key();
  }

  /** Every document library of the shared site, whole. */
  private SharePointSettings settings() {
    String site = sharedSite();
    SourceListing libraries = browse(Map.of("site", site.substring("site:".length())));
    List<Map<String, Object>> chosen = new ArrayList<>();
    for (SourceListing.Entry entry : libraries.entries()) {
      chosen.add(Map.of("driveId", drive(entry).driveId()));
    }
    return SharePointSettings.read(ConnectorData.of(Map.of("libraries", chosen)));
  }

  private static SharePointLibrary drive(SourceListing.Entry entry) {
    return SharePointLibrary.fromJson(
        Map.of("driveId", entry.key().substring(SharePointLibrary.KEY_PREFIX.length())));
  }

  private DriveFileStore store(SharePointSettings settings) {
    RequestBudget budget = RequestBudget.unbounded();
    return new DriveFileStore(
        DriveRule.SHAREPOINT,
        graphs.open(source(), () -> token, budget),
        budget.meter(),
        settings.libraries(),
        graphs.properties().pageSize());
  }

  private SourceSettings source() {
    return new SourceSettings(
        null, SharePointSourceConnector.API_BASE.toString(), token, settings().toData());
  }

  private SourceListing browse(Map<String, Object> query) {
    return connector.browse(
        new SourceBrowser.Query(
            new SourceSettings(
                null,
                SharePointSourceConnector.API_BASE.toString(),
                token,
                ConnectorData.of(new LinkedHashMap<>(query))),
            null));
  }

  private static Map<String, String> read(Path file) throws Exception {
    Map<String, String> values = new LinkedHashMap<>();
    for (String line : Files.readAllLines(file)) {
      String trimmed = line.strip();
      int equals = trimmed.indexOf('=');
      if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 1) {
        continue;
      }
      values.put(trimmed.substring(0, equals).strip(), trimmed.substring(equals + 1).strip());
    }
    for (String key :
        List.of(
            "OPAA_SP_TENANT_ID",
            "OPAA_SP_CLIENT_ID",
            "OPAA_SP_CLIENT_SECRET",
            "OPAA_SP_SITE_URL")) {
      assertThat(values.get(key)).as(key + " in the credentials file").isNotBlank();
    }
    return values;
  }
}
