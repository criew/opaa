package io.opaa.search;

import io.opaa.library.LibraryAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfiguration {

  /**
   * The fallback effective view - a person's own readable libraries. Conditional so #1718's
   * token-aware {@link SearchScopeSource} displaces it, and a fallback rather than nothing so a
   * deployment without that source still searches with the caller's own rights.
   */
  @Bean
  @ConditionalOnMissingBean(SearchScopeSource.class)
  SearchScopeSource personSearchScopeSource(LibraryAccessService libraryAccessService) {
    return new PersonSearchScopeSource(libraryAccessService);
  }
}
