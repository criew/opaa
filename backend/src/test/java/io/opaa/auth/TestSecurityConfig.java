package io.opaa.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@TestConfiguration
public class TestSecurityConfig {

  // Mirrors OidcSecurityConfig/DevSecurityConfig: a @WebMvcTest-sliced controller test needs
  // UserProvisioningFilter in its own chain to populate the CurrentUser request attribute
  // CurrentUserArgumentResolver reads - this config has no oauth2ResourceServer(...) configurer to
  // add it after, so it anchors on SecurityContextHolderFilter, always present by default.
  @Bean
  SecurityFilterChain testSecurityFilterChain(HttpSecurity http, UserService userService)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .addFilterAfter(new UserProvisioningFilter(userService), SecurityContextHolderFilter.class)
        .build();
  }
}
