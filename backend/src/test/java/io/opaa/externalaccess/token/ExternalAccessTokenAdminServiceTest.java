package io.opaa.externalaccess.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The administration's levers reach the access tokens of its own organization only. */
class ExternalAccessTokenAdminServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

  private final UUID organization = UUID.randomUUID();
  private final UUID otherOrganization = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();

  private ExternalAccessTokenRepository tokens;
  private UserRepository users;
  private ExternalAccessTokenAdminService service;

  @BeforeEach
  void setUp() {
    tokens = mock(ExternalAccessTokenRepository.class);
    users = mock(UserRepository.class);
    service =
        new ExternalAccessTokenAdminService(
            tokens,
            users,
            mock(ExternalAccessTokenService.class),
            mock(AuditEventRecorder.class),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private User personOf(UUID organizationId) {
    User user = new User(UUID.randomUUID().toString(), "issuer", "p@example.org", "Person");
    user.setOrganizationId(organizationId);
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    return user;
  }

  private ExternalAccessToken tokenOf(User owner) {
    ExternalAccessToken token =
        new ExternalAccessToken(
            owner.getId(),
            "Zugang",
            "opaa_abc",
            UUID.randomUUID().toString(),
            NOW.minusSeconds(60),
            NOW.plusSeconds(86_400),
            List.of(UUID.randomUUID()));
    when(tokens.findById(token.getId())).thenReturn(Optional.of(token));
    return token;
  }

  // regression guard for #3358 (6): a token of another organization is unknown here
  @Test
  void aTokenOfAnotherOrganizationIsNotBlocked() {
    ExternalAccessToken foreign = tokenOf(personOf(otherOrganization));

    assertThatThrownBy(() -> service.block(foreign.getId(), actor, organization))
        .isInstanceOf(NotFoundException.class);
    assertThat(foreign.getRevokedAt()).isNull();
    verify(tokens, never()).save(any());
  }

  // regression guard for #3358 (6): nor are the tokens of a person of another organization
  @Test
  void thePersonOfAnotherOrganizationIsUnknownToTheBlockByOwner() {
    User foreign = personOf(otherOrganization);

    assertThatThrownBy(() -> service.blockAllOf(foreign.getId(), actor, organization))
        .isInstanceOf(NotFoundException.class);
    verify(tokens, never()).findByUserIdAndRevokedAtIsNull(any());
  }

  @Test
  void aTokenOfTheOwnOrganizationIsBlocked() {
    ExternalAccessToken own = tokenOf(personOf(organization));

    service.block(own.getId(), actor, organization);

    assertThat(own.getRevokedAt()).isEqualTo(NOW);
  }
}
