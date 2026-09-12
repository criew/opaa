package io.opaa.auth.local;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one read of a person's own local account outside this package (ADR-0033, Entscheidung 11):
 * the creation reason a system administrator recorded is part of the person's own self-disclosure,
 * so {@code GET /api/v1/auth/me} carries it and the user settings show it. Empty for an account of
 * an identity provider, which has no {@code local_credentials} row - "absent" therefore means "not
 * a local account", never "reason unknown".
 */
@Service
public class LocalAccountSelfDisclosure {

  private final LocalCredentialsRepository credentials;

  public LocalAccountSelfDisclosure(LocalCredentialsRepository credentials) {
    this.credentials = credentials;
  }

  @Transactional(readOnly = true)
  public Optional<String> createdReasonOf(UUID userId) {
    return credentials.findById(userId).map(LocalCredentials::getCreatedReason);
  }
}
