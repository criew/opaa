package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The daily run is the one place the local account management's housekeeping hangs (ADR-0033,
 * Entscheidung 7): after the token cleanup every registered {@link LocalAccountMaintenanceStep}
 * runs with the same instant - the inactivity lock (#1537) and the expiry reminders (#1538) plug
 * in there - and one failing step is logged and never stops the others.
 */
class LocalTokenCleanupServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-11T03:20:00Z");

  @Test
  void runsEveryMaintenanceStepAfterTheCleanupAndSurvivesAFailingOne() {
    LocalRefreshTokenRepository refresh = mock(LocalRefreshTokenRepository.class);
    LocalRevokedTokenRepository revoked = mock(LocalRevokedTokenRepository.class);
    LocalActionTokenRepository action = mock(LocalActionTokenRepository.class);
    when(refresh.deleteExpiredBefore(any())).thenReturn(3);
    when(revoked.deleteExpiredBefore(any())).thenReturn(2);
    when(action.deleteExpiredBefore(any())).thenReturn(1);
    List<String> ran = new ArrayList<>();
    LocalAccountMaintenanceStep failing =
        new LocalAccountMaintenanceStep() {
          @Override
          public String name() {
            return "failing";
          }

          @Override
          public void run(Instant now) {
            ran.add("failing@" + now);
            throw new IllegalStateException("boom");
          }
        };
    LocalAccountMaintenanceStep second =
        new LocalAccountMaintenanceStep() {
          @Override
          public String name() {
            return "second";
          }

          @Override
          public void run(Instant now) {
            ran.add("second@" + now);
          }
        };
    LocalTokenCleanupService service =
        new LocalTokenCleanupService(
            refresh, revoked, action, List.of(failing, second), Clock.fixed(NOW, ZoneOffset.UTC));

    LocalTokenCleanupService.Result result = service.runOnce();

    assertThat(result).isEqualTo(new LocalTokenCleanupService.Result(3, 2, 1));
    assertThat(ran).containsExactly("failing@" + NOW, "second@" + NOW);
  }

  @Test
  void theCutoffIsExactlyTheRetentionBeforeNow() {
    LocalRefreshTokenRepository refresh = mock(LocalRefreshTokenRepository.class);
    LocalTokenCleanupService service =
        new LocalTokenCleanupService(
            refresh,
            mock(LocalRevokedTokenRepository.class),
            mock(LocalActionTokenRepository.class),
            List.of(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    service.runOnce();

    org.mockito.Mockito.verify(refresh)
        .deleteExpiredBefore(NOW.minus(LocalTokenCleanupService.RETENTION));
  }
}
