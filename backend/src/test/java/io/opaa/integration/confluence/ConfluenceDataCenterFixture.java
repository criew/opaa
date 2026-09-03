package io.opaa.integration.confluence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * One real Confluence Data Center per JVM (ADR-0023, #1171): Postgres and Confluence in Docker, the
 * first-run wizard walked over HTTP with a fresh time-bomb licence, an administrator account, two
 * personal access tokens with different rights and a defined body of content. Started lazily on
 * first use and stopped by Ryuk/JVM exit - every test class of the suite shares it, so the
 * expensive part (several minutes) is paid once per run, well inside the licence's three hours.
 *
 * <p>Content (space key - pages):
 *
 * <ul>
 *   <li>{@code ENG} - "Handbuch" (root) &gt; "Kapitel 1" &gt; "Abschnitt 1.1" (with attachments
 *       {@code plan.pdf}, {@code notizen.txt}); "Nur Admin" (root, read-restricted to the admin)
 *   <li>{@code HR} - "Onboarding" (root); "Alt" (root, moved to the trash)
 *   <li>{@code SEC} - "Streng geheim" (root); the second user has no view permission on this space
 * </ul>
 *
 * Tokens: {@link #adminToken()} reads everything, {@link #limitedToken()} belongs to the second
 * user, who reads {@code ENG} and {@code HR} but not {@code SEC} and not the restricted page.
 */
public final class ConfluenceDataCenterFixture {

  private static final Logger log = LoggerFactory.getLogger(ConfluenceDataCenterFixture.class);

  static final String ADMIN_USER = "admin";
  static final String ADMIN_PASSWORD = "Admin-Passwort-2026!";
  static final String LIMITED_USER = "sachbearbeitung";
  static final String LIMITED_PASSWORD = "Sachbearbeitung-2026!";

  private static final DockerImageName CONFLUENCE_IMAGE =
      DockerImageName.parse(
          System.getenv().getOrDefault("OPAA_CONFLUENCE_TEST_IMAGE", "atlassian/confluence:8.5"));

  private static ConfluenceDataCenterFixture instance;

  private final Network network;
  private final PostgreSQLContainer<?> postgres;
  private final GenericContainer<?> confluence;
  private String baseUrl;
  private String adminToken;
  private String limitedToken;
  private final Map<String, String> pageIdsByTitle = new LinkedHashMap<>();
  private String trashedPageId;

  /** The shared instance, started on first call. */
  public static synchronized ConfluenceDataCenterFixture get() {
    if (instance == null) {
      ConfluenceDataCenterFixture fixture = new ConfluenceDataCenterFixture();
      try {
        fixture.start();
      } catch (Exception e) {
        fixture.stop();
        throw new IllegalStateException("Confluence Data Center fixture failed to start", e);
      }
      instance = fixture;
      Runtime.getRuntime().addShutdownHook(new Thread(fixture::stop));
    }
    return instance;
  }

  private ConfluenceDataCenterFixture() {
    network = Network.newNetwork();
    postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"))
            .withNetwork(network)
            .withNetworkAliases("atlassian-db")
            .withDatabaseName("confluencedb")
            .withUsername("confluence")
            .withPassword("confluence")
            .withCommand("postgres", "-c", "max_connections=200");
    confluence =
        new GenericContainer<>(CONFLUENCE_IMAGE)
            .withNetwork(network)
            .dependsOn(postgres)
            .withExposedPorts(8090)
            .withEnv("ATL_JDBC_URL", "jdbc:postgresql://atlassian-db:5432/confluencedb")
            .withEnv("ATL_JDBC_USER", "confluence")
            .withEnv("ATL_JDBC_PASSWORD", "confluence")
            .withEnv("ATL_DB_TYPE", "postgresql")
            .withEnv("JVM_MINIMUM_MEMORY", "1024m")
            .withEnv("JVM_MAXIMUM_MEMORY", "2048m")
            .withEnv("ATL_TOMCAT_SCHEME", "http")
            // Without this the admin account is locked after a handful of automated logins and the
            // symptom looks like a wrong password (atlassian-dc-plugin, docker/docker-compose.yml).
            .withEnv(
                "JVM_SUPPORT_RECOMMENDED_ARGS",
                "-Datlassian.recovery.password.disable.captcha=true"
                    + " -Dconfluence.security.captcha.threshold=99999")
            .waitingFor(
                Wait.forHttp("/status")
                    .forPort(8090)
                    .forStatusCodeMatching(status -> status == 200 || status == 503)
                    .withStartupTimeout(Duration.ofMinutes(12)))
            .withStartupTimeout(Duration.ofMinutes(12));
  }

  /** The address a library stores: host root, no context path. */
  public String baseUrl() {
    return baseUrl;
  }

  public String adminToken() {
    return adminToken;
  }

  public String limitedToken() {
    return limitedToken;
  }

  public String pageId(String title) {
    String id = pageIdsByTitle.get(title);
    if (id == null) {
      throw new IllegalArgumentException("no seeded page titled " + title);
    }
    return id;
  }

  public String trashedPageId() {
    return trashedPageId;
  }

  private void start() throws Exception {
    log.info("starting Postgres and Confluence Data Center ({})", CONFLUENCE_IMAGE);
    postgres.start();
    confluence.start();
    baseUrl = "http://" + confluence.getHost() + ":" + confluence.getMappedPort(8090);
    log.info("confluence answers at {}", baseUrl());
    ConfluenceHttpSession session = new ConfluenceHttpSession(baseUrl());
    waitUntilResponsive(session);
    String licence = TimeBombLicense.fetchConfluence();
    log.info("fetched time-bomb licence ({} chars)", licence.length());
    new ConfluenceSetupWizard(session, ADMIN_USER, ADMIN_PASSWORD).run(licence);
    waitUntilRestReady(session);
    adminToken = createToken(session, ADMIN_USER, ADMIN_PASSWORD, "opaa-admin");
    confirmWebSudo(session);
    seed(session);
    limitedToken =
        createToken(
            new ConfluenceHttpSession(baseUrl()), LIMITED_USER, LIMITED_PASSWORD, "opaa-limited");
    log.info("fixture ready: {} pages seeded", pageIdsByTitle.size());
  }

  private void stop() {
    try {
      confluence.stop();
    } catch (RuntimeException ignored) {
      // best effort
    }
    try {
      postgres.stop();
    } catch (RuntimeException ignored) {
      // best effort
    }
    try {
      network.close();
    } catch (RuntimeException ignored) {
      // best effort
    }
  }

  private void waitUntilResponsive(ConfluenceHttpSession session) throws Exception {
    long deadline = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
    IOException last = null;
    while (System.currentTimeMillis() < deadline) {
      try {
        ConfluenceHttpSession.Page page = session.get(baseUrl() + "/");
        if (page.status() < 500) {
          return;
        }
      } catch (IOException e) {
        last = e;
      }
      Thread.sleep(3000);
    }
    throw new IOException("confluence did not answer within 10 minutes", last);
  }

  private void waitUntilRestReady(ConfluenceHttpSession session) throws Exception {
    long deadline = System.currentTimeMillis() + Duration.ofMinutes(5).toMillis();
    while (System.currentTimeMillis() < deadline) {
      ConfluenceHttpSession.Page page =
          session.rest("GET", "/rest/api/space?limit=1", null, ADMIN_USER, ADMIN_PASSWORD);
      if (page.status() == 200) {
        return;
      }
      Thread.sleep(3000);
    }
    throw new IOException("confluence REST did not become ready after the wizard");
  }

  /** Logs in through the web form and creates a personal access token via REST. */
  private String createToken(
      ConfluenceHttpSession session, String user, String password, String name)
      throws IOException, InterruptedException {
    ConfluenceHttpSession.Page login =
        session.postForm(
            baseUrl() + "/dologin.action",
            ConfluenceHttpSession.fields(
                "os_username",
                user,
                "os_password",
                password,
                "login",
                "Log in",
                "os_destination",
                "/index.action"));
    if (login.url().toString().contains("loginfailed")) {
      throw new IOException("login failed for " + user);
    }
    ConfluenceHttpSession.Page created =
        session.rest(
            "POST",
            "/rest/pat/latest/tokens",
            "{\"name\":\"" + name + "\",\"expirationDuration\":30}",
            user,
            password);
    if (created.status() != 200 && created.status() != 201) {
      throw new IOException(
          "PAT creation for " + user + " failed: HTTP " + created.status() + " " + created.body());
    }
    JsonNode json = ConfluenceHttpSession.json(created);
    String raw = json.path("rawToken").asString(null);
    if (raw == null || raw.isBlank()) {
      throw new IOException("PAT response carried no rawToken: " + created.body());
    }
    return raw;
  }

  /**
   * Administrative calls on a cookie session - the JSON-RPC user administration in particular -
   * need WebSudo ({@code WebSudoRequiredException} otherwise): the password confirmed once more on
   * {@code /doauthenticate.action}, valid for ten minutes, ample for the seeding.
   */
  private void confirmWebSudo(ConfluenceHttpSession session)
      throws IOException, InterruptedException {
    ConfluenceHttpSession.Page challenge =
        session.get(baseUrl() + "/authenticate.action?destination=%2Findex.action");
    ConfluenceHttpSession.Page confirmed =
        session.postForm(
            baseUrl() + "/doauthenticate.action",
            ConfluenceHttpSession.fields(
                "atl_token",
                challenge.atlToken(),
                "password",
                ADMIN_PASSWORD,
                "destination",
                "/index.action",
                "authenticate",
                "Confirm"));
    log.info("websudo -> {} {}", confirmed.status(), confirmed.url());
    if (confirmed.url().toString().contains("authenticate.action")) {
      throw new IOException(
          "websudo confirmation was not accepted: HTTP "
              + confirmed.status()
              + " at "
              + confirmed.url());
    }
  }

  // ---- seeding ---------------------------------------------------------------------------------

  private void seed(ConfluenceHttpSession session) throws IOException, InterruptedException {
    ConfluenceAdminApi admin = new ConfluenceAdminApi(session, ADMIN_USER, ADMIN_PASSWORD);
    admin.createUser(LIMITED_USER, LIMITED_PASSWORD, "Sachbearbeitung", "sb@example.test");

    admin.createSpace("ENG", "Engineering");
    admin.createSpace("HR", "Personal");
    admin.createSpace("SEC", "Geheimschutz");
    admin.removeGroupViewPermission("SEC", "confluence-users");

    String handbuch = admin.createPage("ENG", "Handbuch", null, "<p>Willkommen im Handbuch.</p>");
    String kapitel = admin.createPage("ENG", "Kapitel 1", handbuch, "<p>Das erste Kapitel.</p>");
    String abschnitt =
        admin.createPage(
            "ENG",
            "Abschnitt 1.1",
            kapitel,
            "<h1>Zuständigkeiten</h1><p>Das Bauamt bearbeitet Anträge innerhalb von 14 Tagen.</p>"
                + "<table><tbody><tr><th>Vorgang</th><th>Frist</th></tr>"
                + "<tr><td>Bauantrag</td><td>14 Tage</td></tr></tbody></table>");
    String nurAdmin = admin.createPage("ENG", "Nur Admin", null, "<p>Nur für die Verwaltung.</p>");
    admin.restrictReadToUser(nurAdmin, ADMIN_USER);
    String onboarding = admin.createPage("HR", "Onboarding", null, "<p>Erste Schritte.</p>");
    String alt = admin.createPage("HR", "Alt", null, "<p>Veraltet.</p>");
    admin.trashPage(alt);
    String geheim = admin.createPage("SEC", "Streng geheim", null, "<p>Nicht für alle.</p>");

    admin.uploadAttachment(
        abschnitt, "plan.pdf", "application/pdf", "%PDF-1.4 plan".getBytes(StandardCharsets.UTF_8));
    admin.uploadAttachment(
        abschnitt,
        "notizen.txt",
        "text/plain",
        "Notizen zur Sitzung".getBytes(StandardCharsets.UTF_8));

    pageIdsByTitle.put("Handbuch", handbuch);
    pageIdsByTitle.put("Kapitel 1", kapitel);
    pageIdsByTitle.put("Abschnitt 1.1", abschnitt);
    pageIdsByTitle.put("Nur Admin", nurAdmin);
    pageIdsByTitle.put("Onboarding", onboarding);
    pageIdsByTitle.put("Streng geheim", geheim);
    trashedPageId = alt;
  }
}
