package io.opaa.group.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DirectorySyncProperties.class)
public class DirectorySyncConfiguration {

  /**
   * {@code @ConditionalOnMissingBean} on a {@code @Bean} method (unlike on a plain
   * {@code @Component}) is reliably evaluated after every other configuration class's bean
   * definitions are known, so a future deployment-specific {@link DirectoryClient} bean (real
   * directory connector) always wins over this default without needing to know about this class at
   * all.
   */
  @Bean
  @ConditionalOnMissingBean(DirectoryClient.class)
  DirectoryClient noOpDirectoryClient() {
    return new NoOpDirectoryClient();
  }
}
