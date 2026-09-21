package io.opaa.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@TestConfiguration
@EnableMethodSecurity
public class AdminTestSecurityConfig {

  // See TestSecurityConfig's identical addition for why this filter and this anchor point.
  @Bean
  SecurityFilterChain adminTestSecurityFilterChain(
      HttpSecurity http, UserService userService, AccountStateService accountState)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .addFilterAfter(
            new UserProvisioningFilter(userService, accountState),
            SecurityContextHolderFilter.class)
        .build();
  }

  /**
   * The slice has no service layer, so the filter's account-state check gets a mock here rather
   * than a {@code @MockitoBean} field in every sliced controller test. It answers "not blocked" for
   * every account; the block itself is exercised where it is decided (#1818).
   */
  @Bean
  AccountStateService accountStateService() {
    return org.mockito.Mockito.mock(AccountStateService.class);
  }
}
