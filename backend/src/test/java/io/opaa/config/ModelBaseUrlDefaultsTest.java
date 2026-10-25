package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * The model base URLs {@code application.yml} ships without any environment variable: a locally
 * operated Ollama server for chat, and none for the embedding model, whose address the installation
 * sets. Read from the file itself, because every Spring test signature overrides the chat and
 * embedding addresses with ones that never answer.
 */
class ModelBaseUrlDefaultsTest {

  private static final String LOCAL_OLLAMA = "http://localhost:11434/v1";

  @Test
  void chatDefaultsToALocallyOperatedOllamaServerAndEmbeddingToNone() throws IOException {
    PropertyResolver defaults = profileIndependentDefaults();

    assertThat(defaults.getProperty("spring.ai.openai.base-url")).isEqualTo(LOCAL_OLLAMA);
    assertThat(defaults.getProperty("spring.ai.openai.chat.base-url")).isEqualTo(LOCAL_OLLAMA);
    assertThat(defaults.getProperty("spring.ai.openai.embedding.base-url")).isNull();
    assertThat(defaults.getProperty("opaa.embedding.takeover.base-url")).isEmpty();
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
}
