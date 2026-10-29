package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.account.LocalRefreshTokenService.RotationResult;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** The renewal of a local administrator's session keeps to the networks of the sign-in. */
class LocalRefreshTokenNetworkTest {

  private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
  private static final String RAW = "presented-refresh-token";

  private final LocalRefreshTokenRepository repository = mock(LocalRefreshTokenRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
  private final LocalAuthKeyService keys = mock(LocalAuthKeyService.class);
  private LocalRefreshTokenService service;
  private LocalRefreshToken presented;

  @BeforeEach
  void setUp() {
    LocalAuthProperties properties =
        new LocalAuthProperties(
            null, null, null, null, null, null, null, null, null, List.of("10.0.0.0/8"), null);
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    service =
        new LocalRefreshTokenService(
            repository,
            users,
            credentials,
            mock(OidcProviderRegistry.class),
            keys,
            properties,
            mock(LocalTokenRevocationService.class),
            mock(AuditEventRecorder.class),
            new LocalAdminNetworkPolicy(properties),
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC));

    User admin = User.localAccount("admin@stadt.example", "Admin");
    admin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    when(users.findById(admin.getId())).thenReturn(Optional.of(admin));
    LocalCredentials row = new LocalCredentials(admin.getId(), "Einrichtung", NOW.minusSeconds(60));
    row.setPasswordHash("{bcrypt}$2a$12$stored", NOW.minusSeconds(60));
    row.markEmailVerified(NOW.minusSeconds(60));
    when(credentials.findById(admin.getId())).thenReturn(Optional.of(row));

    presented =
        new LocalRefreshToken(
            UUID.randomUUID(),
            admin.getId(),
            "presented-hash",
            NOW.minusSeconds(600),
            NOW.plus(Duration.ofHours(1)),
            NOW.plus(Duration.ofHours(8)));
    when(keys.lookupHash(eq(Purpose.REFRESH_TOKEN_LOOKUP), anyString()))
        .thenAnswer(call -> RAW.equals(call.getArgument(1)) ? "presented-hash" : "successor-hash");
    when(repository.findByTokenLookupHash("presented-hash")).thenReturn(Optional.of(presented));
    when(repository.save(any(LocalRefreshToken.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(repository.rotateIfActive(any(), any(), any(), any())).thenReturn(1);
  }

  // regression guard for #3358 (6): the network rule of the sign-in holds for the renewal too
  @Test
  void anAdministratorsSessionIsNotRenewedFromOutsideTheNetworks() {
    RotationResult result = service.rotate(RAW, "192.0.2.10");

    assertThat(result).isInstanceOf(RotationResult.Unknown.class);
    verify(repository, never()).rotateIfActive(any(), any(), any(), any());
    verify(repository, never()).revokeFamily(any(), any(), any());
  }

  @Test
  void anAdministratorsSessionIsRenewedFromInsideTheNetworks() {
    RotationResult result = service.rotate(RAW, "10.20.30.40");

    assertThat(result).isInstanceOf(RotationResult.Rotated.class);
  }
}
