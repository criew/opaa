package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Every property with which Spring AI would put prompts, answers, transcripts, retrieved chunks or
 * tool arguments into observations, logs or spans stands explicitly on {@code false} in the base
 * document of {@code application.yml}, so a changed library default cannot switch one on. Any such
 * property a Spring AI module on the classpath declares has to be in {@link #KEYS} (regression
 * guard for ADR-0071, decision 3).
 */
class SpringAiContentPropertiesTest {

  /** The content switches of Spring AI's observations. */
  static final List<String> KEYS =
      List.of(
          "spring.ai.chat.observations.log-prompt",
          "spring.ai.chat.observations.log-completion",
          "spring.ai.chat.client.observations.log-prompt",
          "spring.ai.chat.client.observations.log-completion",
          "spring.ai.image.observations.log-prompt",
          "spring.ai.vectorstore.observations.log-query-response",
          "spring.ai.tools.observations.include-content");

  private static final Pattern CONTENT_SWITCH =
      Pattern.compile(
          "\"name\"\\s*:\\s*\"(spring\\.ai\\.[a-z.-]*\\.(?:log-prompt|log-completion|log-transcript"
              + "|log-query-response|include-content|log-[a-z-]*content[a-z-]*))\"");

  @Test
  void theBaseDocumentSwitchesEveryContentPropertyOff() throws IOException {
    PropertySource<?> base =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"))
            .getFirst();

    for (String key : KEYS) {
      assertThat(base.getProperty(key)).as(key).hasToString("false");
    }
  }

  @Test
  void everyContentPropertySpringAiDeclaresIsListed() throws IOException {
    Set<String> declared = new TreeSet<>();
    for (URL metadata :
        Collections.list(
            getClass()
                .getClassLoader()
                .getResources("META-INF/spring-configuration-metadata.json"))) {
      try (InputStream in = metadata.openStream()) {
        Matcher matcher =
            CONTENT_SWITCH.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        while (matcher.find()) {
          declared.add(matcher.group(1));
        }
      }
    }

    assertThat(declared).isNotEmpty();
    assertThat(KEYS).containsAll(declared);
  }
}
