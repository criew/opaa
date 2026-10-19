package io.opaa.connection.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.UserRepository;
import io.opaa.connection.consent.SourceConsentService;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectionProfileService;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The "client secret expires soon" flag of the administration and the expiry watch's reminder count
 * days in the same zone, the server's: right after midnight server time, while UTC still has the
 * previous day, a secret at the edge of the warning period is flagged on the day it is warned of.
 */
class SecretExpiryDayTest {

  private static final ZoneId SERVER = ZoneId.of("Europe/Berlin");

  /** 00:30 on 17 October in Berlin, 22:30 on 16 October in UTC. */
  private static final Clock JUST_AFTER_MIDNIGHT =
      Clock.fixed(Instant.parse("2026-10-16T22:30:00Z"), SERVER);

  private static final LocalDate EDGE_OF_WARNING =
      LocalDate.of(2026, 10, 17).plusDays(ConnectionProfileService.SECRET_EXPIRY_WARNING_DAYS);

  private final ConnectionProfileRepository profiles = mock(ConnectionProfileRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final ConnectionProfile profile = mock(ConnectionProfile.class);
  private final UUID profileId = UUID.randomUUID();

  @Test
  void aSecretAtTheEdgeOfTheWarningIsFlaggedOnTheDayItIsWarnedOf() {
    when(profile.getId()).thenReturn(profileId);
    when(profile.getClientSecretExpiresOn()).thenReturn(EDGE_OF_WARNING);
    when(profiles.lockSecretsExpiringUnwarned(any()))
        .thenAnswer(
            call ->
                EDGE_OF_WARNING.isAfter(call.getArgument(0, LocalDate.class))
                    ? List.of()
                    : List.of(profileId));
    when(profiles.findAllById(List.of(profileId))).thenReturn(List.of(profile));
    when(users.findBySystemRole(SystemRole.SYSTEM_ADMIN)).thenReturn(List.of());

    int warned = watch().warn().secrets();
    boolean flagged = service().secretExpiresSoon(profile);

    assertThat(warned).isEqualTo(1);
    assertThat(flagged).isTrue();
  }

  @Test
  void aSecretADayBeyondTheWarningIsNeitherFlaggedNorWarnedOf() {
    when(profile.getClientSecretExpiresOn()).thenReturn(EDGE_OF_WARNING.plusDays(1));
    when(profiles.lockSecretsExpiringUnwarned(any()))
        .thenAnswer(
            call ->
                EDGE_OF_WARNING.plusDays(1).isAfter(call.getArgument(0, LocalDate.class))
                    ? List.of()
                    : List.of(profileId));

    int warned = watch().warn().secrets();
    boolean flagged = service().secretExpiresSoon(profile);

    assertThat(warned).isZero();
    assertThat(flagged).isFalse();
  }

  private ConnectionExpiryWatch watch() {
    return new ConnectionExpiryWatch(
        mock(ConnectionSecrets.class),
        mock(SourceConsentService.class),
        mock(ConnectedAccountRepository.class),
        profiles,
        users,
        mock(NotificationService.class),
        mock(PlatformTransactionManager.class),
        JUST_AFTER_MIDNIGHT);
  }

  private ConnectionProfileService service() {
    return new ConnectionProfileService(
        profiles,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        JUST_AFTER_MIDNIGHT);
  }
}
