package io.opaa.auth;

import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile({"oidc", "basic"})
public class UserService {

  private final UserRepository userRepository;

  public UserService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Transactional
  public User findOrCreateUser(String subject, String issuer, String email, String displayName) {
    return userRepository
        .findBySubjectAndIssuer(subject, issuer)
        .map(
            existing -> {
              existing.setLastLoginAt(Instant.now());
              if (email != null) {
                existing.setEmail(email);
              }
              if (displayName != null) {
                existing.setDisplayName(displayName);
              }
              return userRepository.save(existing);
            })
        .orElseGet(
            () -> {
              User newUser = new User(subject, issuer, email, displayName);
              return userRepository.save(newUser);
            });
  }
}
