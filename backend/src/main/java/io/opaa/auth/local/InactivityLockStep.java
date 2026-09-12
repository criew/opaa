package io.opaa.auth.local;

import io.opaa.api.types.LocalAccountState;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The lock after the inactivity period (ADR-0033, Entscheidung 9): an {@code ACTIVE} local account
 * whose last activity - the throttled {@code users.last_login_at}, or the creation for an account
 * that never signed in - lies more than {@code local_auth_settings.inactive_days} back is locked
 * with reason {@code INACTIVITY}, audited under the {@code local-auth} system actor and told by
 * mail. The bootstrap account is exempt (its purpose is to stay unused); the last login-capable
 * system administrator is skipped with a WARN line naming the account id, never the address. Two
 * queries for the whole population, one transaction per lock, at most {@link #MAX_LOCKS_PER_RUN}
 * locks (and mails) per run - the rest waits for the next day: unlike the reminders, this step
 * catches up, because an account inactive today is inactive tomorrow too.
 */
@Component
@Order(10)
public class InactivityLockStep implements LocalAccountMaintenanceStep {

  public static final int MAX_LOCKS_PER_RUN = 200;

  private static final Logger log = LoggerFactory.getLogger(InactivityLockStep.class);

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final LocalAuthSettingsRepository settings;
  private final LocalUserService accounts;
  private final LocalAccountMailer mailer;

  public InactivityLockStep(
      UserRepository users,
      LocalCredentialsRepository credentials,
      LocalAuthSettingsRepository settings,
      LocalUserService accounts,
      LocalAccountMailer mailer) {
    this.users = users;
    this.credentials = credentials;
    this.settings = settings;
    this.accounts = accounts;
    this.mailer = mailer;
  }

  @Override
  public String name() {
    return "inactivity-lock";
  }

  @Override
  public void run(Instant now) {
    int inactiveDays =
        settings
            .findSingleton()
            .map(LocalAuthSettings::getInactiveDays)
            .orElse(LocalAuthSettings.Values.defaults().inactiveDays());
    Instant cutoff = now.minus(Duration.ofDays(inactiveDays));
    List<User> localUsers = users.findByIssuer(LocalIssuer.URN);
    Map<UUID, LocalCredentials> rows =
        credentials.findAllById(localUsers.stream().map(User::getId).toList()).stream()
            .collect(Collectors.toMap(LocalCredentials::getUserId, Function.identity()));
    int locked = 0;
    for (User user : localUsers) {
      LocalCredentials row = rows.get(user.getId());
      if (row == null || row.isBootstrap() || row.state(now) != LocalAccountState.ACTIVE) {
        continue;
      }
      Instant lastActivity =
          user.getLastLoginAt() != null ? user.getLastLoginAt() : user.getCreatedAt();
      if (!lastActivity.isBefore(cutoff)) {
        continue;
      }
      if (locked >= MAX_LOCKS_PER_RUN) {
        log.warn(
            "Inactivity lock: stopped after {} locks; further inactive accounts wait for the next"
                + " run",
            MAX_LOCKS_PER_RUN);
        break;
      }
      try {
        accounts.lockForInactivity(user.getId());
      } catch (ConflictException lastAdministrator) {
        log.warn(
            "Local account {} has been inactive for more than {} days but is the last"
                + " login-capable system administrator; not locked",
            user.getId(),
            inactiveDays);
        continue;
      }
      locked++;
      mailer.sendLockedForInactivity(user, inactiveDays);
    }
    log.info("Inactivity lock: {} local account(s) locked after {} days", locked, inactiveDays);
  }
}
