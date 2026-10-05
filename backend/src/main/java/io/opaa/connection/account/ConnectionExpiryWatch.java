package io.opaa.connection.account;

import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.NotificationType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.connection.consent.SourceConsentService;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectionProfileService;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.ConnectionSecrets.EndingConsent;
import io.opaa.connection.token.ConnectionSecrets.EndingGrant;
import io.opaa.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Warns once of every end ahead (ADR-0041): the end a provider named for a person's OAuth consent
 * goes to that person, for a library's own consent to those responsible for it ({@link
 * SourceConsentService}), the expiry date of a profile's client secret to every system
 * administrator, {@value ConnectionProfileService#SECRET_EXPIRY_WARNING_DAYS} days before. The
 * marker and the notifications commit together, so a failed run warns on the next one and no end
 * twice. Both kinds of date are read and written in the server's zone.
 */
@Component
public class ConnectionExpiryWatch {

  private static final Logger log = LoggerFactory.getLogger(ConnectionExpiryWatch.class);
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

  private final ConnectionSecrets secrets;
  private final SourceConsentService consents;
  private final ConnectedAccountRepository accounts;
  private final ConnectionProfileRepository profiles;
  private final UserRepository users;
  private final NotificationService notifications;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final ZoneId zone = ZoneId.systemDefault();

  ConnectionExpiryWatch(
      ConnectionSecrets secrets,
      SourceConsentService consents,
      ConnectedAccountRepository accounts,
      ConnectionProfileRepository profiles,
      UserRepository users,
      NotificationService notifications,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.secrets = secrets;
    this.consents = consents;
    this.accounts = accounts;
    this.profiles = profiles;
    this.users = users;
    this.notifications = notifications;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Scheduled(cron = "0 45 4 * * *")
  void daily() {
    try {
      Warned warned = warn();
      log.info(
          "Expiry watch warned of {} OAuth consents of persons, {} of libraries and {} client"
              + " secrets",
          warned.grants(),
          warned.libraryConsents(),
          warned.secrets());
    } catch (RuntimeException e) {
      log.warn("Expiry watch failed; the next run warns of what this one missed", e);
    }
  }

  /** Sends every warning now due, in one transaction; returns how many ends were warned of. */
  public Warned warn() {
    return transactions.execute(
        status -> new Warned(warnOfGrants(), warnOfConsents(), warnOfSecrets()));
  }

  private int warnOfConsents() {
    int warned = 0;
    for (EndingConsent consent : secrets.claimEndingConsents()) {
      consents.warnOfEnd(
          consent.libraryId(), consent.profileId(), DATE.format(consent.endsAt().atZone(zone)));
      warned++;
    }
    return warned;
  }

  private int warnOfGrants() {
    int warned = 0;
    for (EndingGrant grant : secrets.claimEndingGrants()) {
      ConnectedAccount account = accounts.findById(grant.connectedAccountId()).orElse(null);
      ConnectionProfile profile = profiles.findById(grant.profileId()).orElse(null);
      if (account == null || profile == null) {
        continue;
      }
      notifications.notify(
          account.getOrganizationId(),
          account.getUserId(),
          NotificationType.CONNECTION_EXPIRING,
          AuditObjectType.SYSTEM_SETTING,
          profile.getId(),
          "Verbindung läuft ab: Zugang „" + profile.getName() + "“",
          "Der Anbieter beendet die Zustimmung Ihres verbundenen Kontos am "
              + DATE.format(grant.endsAt().atZone(zone))
              + (profile.isMcpServer()
                  // MCP connections are not shown on the accounts page yet
                  ? ". Danach muss die Verbindung zum MCP-Server neu hergestellt werden."
                  : ". Verbinden Sie es vorher auf der Seite „Verbundene Konten“ neu; sonst wird"
                      + " danach nichts aus diesem Zugang aktualisiert."));
      warned++;
    }
    return warned;
  }

  private int warnOfSecrets() {
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, zone);
    List<UUID> due =
        profiles.lockSecretsExpiringUnwarned(
            today.plusDays(ConnectionProfileService.SECRET_EXPIRY_WARNING_DAYS));
    if (due.isEmpty()) {
      return 0;
    }
    profiles.markSecretExpiryWarned(due, now);
    List<User> administrators = users.findBySystemRole(SystemRole.SYSTEM_ADMIN);
    for (ConnectionProfile profile : profiles.findAllById(due)) {
      LocalDate expiresOn = profile.getClientSecretExpiresOn();
      String when =
          expiresOn.isBefore(today)
              ? "ist am " + DATE.format(expiresOn) + " abgelaufen"
              : "läuft am " + DATE.format(expiresOn) + " ab";
      for (User administrator : administrators) {
        notifications.notify(
            administrator.getOrganizationId(),
            administrator.getId(),
            NotificationType.CONNECTION_PROFILE_SECRET_EXPIRING,
            AuditObjectType.SYSTEM_SETTING,
            profile.getId(),
            "Client-Secret läuft ab: Zugang „" + profile.getName() + "“",
            "Das Client-Secret des Zugangs "
                + when
                + ". Tragen Sie unter Administration → Zugänge ein neues Secret und dessen"
                + " Ablaufdatum ein; mit einem abgelaufenen Secret meldet sich der Zugang beim"
                + " Anbieter nicht mehr an.");
      }
    }
    return due.size();
  }

  /** How many OAuth consents of persons and of libraries and client secrets a run warned of. */
  public record Warned(int grants, int libraryConsents, int secrets) {}
}
