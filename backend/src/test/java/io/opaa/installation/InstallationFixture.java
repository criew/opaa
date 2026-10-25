package io.opaa.installation;

import com.sun.net.httpserver.HttpServer;
import io.opaa.test.LocalAccountFixtures;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts the shared test database back into a pending installation and tidies up what a completion
 * created there: the emergency account (through {@link LocalAccountFixtures}), the chat model and
 * identity provider of the installation, its audit entries. The active chat model found before is
 * active again afterwards. {@code installation_state} itself comes back through {@code
 * SeededRowRestorer}. A local chat endpoint answers every probe with a completion.
 */
final class InstallationFixture implements AutoCloseable {

  static final String TOKEN = "installations-token-fuer-die-tests-0123456789-abcdef";
  static final String ADMIN_EMAIL = "it-betrieb@installation.test.example";
  static final String ADMIN_PASSWORD = "Erstes-Konto-der-Installation-2026";

  private final JdbcTemplate jdbc;
  private final InstallationService installation;
  private final DatabaseInstallationStatus status;
  private final LocalAccountFixtures accounts;
  private final List<UUID> activeBefore;
  private final HttpServer chatEndpoint;

  InstallationFixture(
      JdbcTemplate jdbc,
      InstallationService installation,
      DatabaseInstallationStatus status,
      LocalAccountFixtures accounts)
      throws IOException {
    this.jdbc = jdbc;
    this.installation = installation;
    this.status = status;
    this.accounts = accounts;
    this.activeBefore = jdbc.queryForList("SELECT id FROM llm_models WHERE active", UUID.class);
    this.chatEndpoint =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    chatEndpoint.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] body =
              "{\"choices\":[{\"message\":{\"content\":\"Pong\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    chatEndpoint.start();
    accounts.cleanUp();
    resetToPending();
  }

  /** The address of the local chat endpoint, as a chat model step names it. */
  String chatBaseUrl() {
    return "http://127.0.0.1:" + chatEndpoint.getAddress().getPort() + "/v1";
  }

  /** A pending installation with {@link #TOKEN}, no saved step, and the status re-read. */
  void resetToPending() {
    jdbc.update("DELETE FROM installation_steps");
    jdbc.update(
        "UPDATE installation_state SET status = 'PENDING', completion_source = NULL,"
            + " completed_at = NULL, token_hash = NULL, token_source = NULL WHERE id = 1");
    installation.ensureToken(TOKEN);
    status.reload();
  }

  @Override
  public void close() {
    chatEndpoint.stop(0);
    jdbc.update("DELETE FROM installation_steps");
    accounts.cleanUp();
    jdbc.update(
        "DELETE FROM llm_models WHERE display_name = ?",
        InstallationService.CHAT_MODEL_DISPLAY_NAME);
    for (UUID id : activeBefore) {
      jdbc.update("UPDATE llm_models SET active = true WHERE id = ?", id);
    }
    jdbc.update("DELETE FROM oidc_providers WHERE issuer_uri LIKE 'https://idp.test.example/%'");
    jdbc.update("DELETE FROM audit_log WHERE actor_ref = ?", InstallationService.SYSTEM_ACTOR);
    status.reload();
  }
}
