package io.opaa.auth;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Provides {@link RecentLoginPolicy} and registers {@link RecentLoginInterceptor} for every
 * handler, in every auth mode. Self-contained (properties and clock only), because every
 * {@code @WebMvcTest} slice picks up a {@link WebMvcConfigurer}.
 */
@Configuration
@EnableConfigurationProperties(RecentLoginProperties.class)
public class RecentLoginWebConfig implements WebMvcConfigurer {

  private final RecentLoginPolicy policy;

  public RecentLoginWebConfig(RecentLoginProperties properties, Clock clock) {
    this.policy = new RecentLoginPolicy(properties, clock);
  }

  @Bean
  RecentLoginPolicy recentLoginPolicy() {
    return policy;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new RecentLoginInterceptor(policy));
  }
}
