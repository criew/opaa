package io.opaa.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.retrieval.RelevanceCutoffProperties.Mode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class RelevanceCutoffPropertiesTest {

  /**
   * {@link RelevanceCutoffProperties#DEFAULT} is the shipped configuration, unset and from
   * application.yml alike; the committed eval baselines are held against this constant.
   */
  @Test
  void theDefaultIsTheShippedConfiguration() throws IOException {
    RelevanceCutoffProperties unset =
        bind(List.<PropertySource<?>>of(new MapPropertySource("empty", Map.of())));
    List<PropertySource<?>> yaml =
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));

    assertThat(unset).isEqualTo(RelevanceCutoffProperties.DEFAULT);
    assertThat(bind(yaml)).isEqualTo(RelevanceCutoffProperties.DEFAULT);
    assertThat(RelevanceCutoffProperties.DEFAULT)
        .isEqualTo(new RelevanceCutoffProperties(Mode.FIXED, 10, 0.6, 0.25));
  }

  private static RelevanceCutoffProperties bind(List<PropertySource<?>> sources) {
    return new Binder(
            ConfigurationPropertySources.from(sources),
            new PropertySourcesPlaceholdersResolver(sources))
        .bindOrCreate("opaa.query.relevance-cutoff", RelevanceCutoffProperties.class);
  }
}
