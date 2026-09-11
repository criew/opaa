package io.opaa.security;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The one {@link PasswordEncoder} of the local account management (ADR-0033, Entscheidung 9):
 * BCrypt with cost {@value #BCRYPT_COST} behind a {@link DelegatingPasswordEncoder}, so every
 * stored hash carries its algorithm id ({@code {bcrypt}}) and a later switch to Argon2id is a new
 * entry here plus a re-hash on the next successful sign-in ({@link
 * PasswordEncoder#upgradeEncoding}). Comparison happens only through {@link
 * PasswordEncoder#matches} - never by string equality. BCrypt processes at most 72 bytes; the
 * password policy makes that limit visible to the user.
 */
@Configuration
public class PasswordEncoderConfiguration {

  static final String BCRYPT_ID = "bcrypt";
  static final int BCRYPT_COST = 12;

  @Bean
  public PasswordEncoder passwordEncoder() {
    Map<String, PasswordEncoder> encoders =
        Map.of(BCRYPT_ID, new BCryptPasswordEncoder(BCRYPT_COST));
    return new DelegatingPasswordEncoder(BCRYPT_ID, encoders);
  }
}
