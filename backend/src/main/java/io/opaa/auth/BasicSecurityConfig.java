package io.opaa.auth;

import jakarta.annotation.PostConstruct;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@Profile("basic")
@EnableConfigurationProperties(AuthProperties.class)
@EnableMethodSecurity
public class BasicSecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(BasicSecurityConfig.class);
  private static final String INSECURE_DEFAULT_SECRET =
      "change-me-to-a-256-bit-secret-key-in-production!!";

  private final AuthProperties authProperties;
  private final UserService userService;

  public BasicSecurityConfig(AuthProperties authProperties, UserService userService) {
    this.authProperties = authProperties;
    this.userService = userService;
  }

  @PostConstruct
  void validateBasicAuthConfiguration() {
    AuthProperties.BasicAuth basic = authProperties.basic();
    if (basic.secret() == null
        || basic.secret().isBlank()
        || INSECURE_DEFAULT_SECRET.equals(basic.secret())) {
      throw new IllegalStateException(
          "OPAA_AUTH_BASIC_SECRET must be set to a strong value when the basic profile is active");
    }

    if (basic.password() != null && !basic.password().startsWith("{")) {
      log.warn("Basic auth password is configured as plaintext; prefer an encoded password value");
    }
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/health")
                    .permitAll()
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/info",
                        "/actuator/metrics",
                        "/actuator/prometheus")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/login")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/config")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder())))
        .addFilterAfter(
            new UserProvisioningFilter(userService), BearerTokenAuthenticationFilter.class)
        .build();
  }

  @Bean
  JwtDecoder jwtDecoder() {
    byte[] secret = authProperties.basic().secret().getBytes();
    return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
  }

  @Bean
  JwtTokenService jwtTokenService() {
    AuthProperties.BasicAuth basic = authProperties.basic();
    return new JwtTokenService(basic.secret(), basic.tokenExpirationSeconds(), basic.issuer());
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }
}
