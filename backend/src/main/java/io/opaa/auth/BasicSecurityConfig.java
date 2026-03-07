package io.opaa.auth;

import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;

@Configuration
@Profile("basic")
@EnableConfigurationProperties(AuthProperties.class)
@EnableMethodSecurity
public class BasicSecurityConfig {

  @Value("${opaa.cors.allowed-origins}")
  private String allowedOrigins;

  private final AuthProperties authProperties;
  private final UserService userService;

  public BasicSecurityConfig(AuthProperties authProperties, UserService userService) {
    this.authProperties = authProperties;
    this.userService = userService;
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .cors(
            cors ->
                cors.configurationSource(
                    request -> {
                      var config = new CorsConfiguration();
                      config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
                      config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                      config.setAllowedHeaders(
                          List.of("Content-Type", "Authorization", "X-Requested-With"));
                      return config;
                    }))
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/health")
                    .permitAll()
                    .requestMatchers("/actuator/**")
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
}
