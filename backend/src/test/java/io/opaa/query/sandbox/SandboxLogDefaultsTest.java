package io.opaa.query.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The bundled sandbox server keeps scripts and files out of its log and rotates it (ADR-0073): its
 * log level stays at INFO or above, and the Compose overlay bounds the log on the host.
 */
class SandboxLogDefaultsTest {

  private static final Path COMPOSE = Path.of("../docker-compose.sandbox.yml");
  private static final Path SERVER_CONFIG = Path.of("../sandbox/config.toml");
  private static final List<String> CONTENT_FREE_LEVELS =
      List.of("INFO", "WARNING", "WARN", "ERROR", "CRITICAL");

  @Test
  void theServerLogsAtInfoOrAbove() throws IOException {
    String config = Files.readString(SERVER_CONFIG);
    Matcher section = Pattern.compile("(?ms)^\\[log]\\s*$(.*?)(?=^\\[|\\z)").matcher(config);
    assertThat(section.find()).as("[log] section in %s", SERVER_CONFIG).isTrue();
    Matcher level =
        Pattern.compile("(?m)^\\s*level\\s*=\\s*\"([A-Za-z]+)\"").matcher(section.group(1));
    assertThat(level.find()).as("level in [log]").isTrue();
    assertThat(level.group(1).toUpperCase()).isIn(CONTENT_FREE_LEVELS);
  }

  @Test
  @SuppressWarnings("unchecked")
  void composeRotatesTheServerLog() throws IOException {
    Map<String, Object> compose = new Yaml().load(Files.readString(COMPOSE));
    Map<String, Object> services = (Map<String, Object>) compose.get("services");
    Map<String, Object> server = (Map<String, Object>) services.get("opensandbox-server");
    Map<String, Object> logging = (Map<String, Object>) server.get("logging");
    assertThat(logging).as("logging of opensandbox-server").isNotNull();
    Map<String, Object> options = (Map<String, Object>) logging.get("options");
    assertThat(options).containsKeys("max-size", "max-file");
  }
}
