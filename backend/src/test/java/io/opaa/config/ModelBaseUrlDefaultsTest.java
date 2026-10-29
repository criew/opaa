package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * The model address and key {@code application.yml} ships without any environment variable: only
 * the {@code dev} mode reads them, for the chat and embedding model it installs itself with - a
 * locally operated Ollama server, in the Compose stack its {@code ollama} service. Every other mode
 * gets its models from the installation, so no other document sets them. Read from the file itself,
 * because every Spring test signature overrides the addresses with ones that never answer.
 */
class ModelBaseUrlDefaultsTest {

  private static final String LOCAL_OLLAMA = "http://localhost:11434/v1";
  private static final String COMPOSE_OLLAMA = "http://ollama:11434/v1";
  private static final String BASE_URL = "spring.ai.openai.base-url";
  private static final String API_KEY = "spring.ai.openai.api-key";

  @Test
  void theBaseDocumentSetsNoModelAddressAndNoKey() throws IOException {
    PropertyResolver defaults = profileIndependentDefaults();

    assertThat(defaults.getProperty(BASE_URL)).isNull();
    assertThat(defaults.getProperty(API_KEY)).isNull();
    assertThat(defaults.getProperty("spring.ai.openai.chat.base-url")).isNull();
    assertThat(defaults.getProperty("spring.ai.openai.embedding.base-url")).isNull();
    assertThat(defaults.getProperty("opaa.embedding.takeover.base-url")).isNull();
  }

  @Test
  void theDevModeDefaultsToALocallyOperatedOllamaServer() {
    try (ConfigurableApplicationContext context = start("local", "dev")) {
      Environment environment = context.getEnvironment();

      assertThat(environment.getProperty(BASE_URL)).isEqualTo(LOCAL_OLLAMA);
      assertThat(environment.getProperty(API_KEY)).isEqualTo("sk-placeholder");
    }
  }

  @Test
  void theDevModeOfTheComposeStackDefaultsToItsOllamaService() {
    try (ConfigurableApplicationContext context = start("docker", "dev")) {
      assertThat(context.getEnvironment().getProperty(BASE_URL)).isEqualTo(COMPOSE_OLLAMA);
    }
  }

  @Test
  void theOperatingModeReadsNoModelAddressAndNoKey() {
    try (ConfigurableApplicationContext context = start("docker", "oidc")) {
      Environment environment = context.getEnvironment();

      assertThat(environment.getProperty(BASE_URL)).isNull();
      assertThat(environment.getProperty(API_KEY)).isNull();
    }
  }

  /** A context of nothing but the real {@code application.yml} under {@code profiles}. */
  private static ConfigurableApplicationContext start(String... profiles) {
    return new SpringApplicationBuilder(Empty.class)
        .web(WebApplicationType.NONE)
        .profiles(profiles)
        .run();
  }

  /** The first document of {@code application.yml}, placeholders resolved to their defaults. */
  private static PropertyResolver profileIndependentDefaults() throws IOException {
    List<PropertySource<?>> documents =
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
    PropertySource<?> first = documents.getFirst();
    assertThat(first.containsProperty("spring.config.activate.on-profile")).isFalse();
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(first);
    return new PropertySourcesPropertyResolver(sources);
  }

  @Configuration(proxyBeanMethods = false)
  static class Empty {}
}
