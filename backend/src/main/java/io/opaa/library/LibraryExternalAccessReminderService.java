package io.opaa.library;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.PublicBaseUrl;
import io.opaa.compliance.DataFlow;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.mail.MailService;
import io.opaa.mail.MailTemplateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Wiedervorlage before a Freigabe für Fremdzugänge expires (ADR-0033's mail mechanics). The
 * Befristung makes the renewal a deliberate act; this is what keeps it from being a surprise.
 *
 * <p>Goes to the person who last set the release - they made the decision and are the one who can
 * renew it - falling back to the library's owner where that account is gone and the library is
 * owned by a person. A group-owned library with no reachable setter is logged and skipped rather
 * than mailed to a group address the product does not have; the release still expires on its own,
 * which is the behaviour that matters.
 *
 * <p>Sends at most one mail per release: {@code external_access_reminder_sent_at} is written in the
 * transaction that queues the mail to the outbox ({@link MailService#enqueue}), and it is cleared
 * by every change of the release, so a renewed release earns a fresh reminder. The releases are
 * selected under a row lock, so a run on another instance at the same moment waits and then finds
 * the marker set. The mail leaves
 * after the commit and only with it - a rolled-back run sends nothing - and its delivery retries
 * are the outbox's; the run itself is not repeated the next day.
 */
@DataFlow("mail")
@Service
public class LibraryExternalAccessReminderService {

  private static final Logger log =
      LoggerFactory.getLogger(LibraryExternalAccessReminderService.class);

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("'am' dd.MM.yyyy", Locale.GERMANY);

  private final KnowledgeLibraryRepository libraryRepository;
  private final UserRepository userRepository;
  private final MailService mail;
  private final PublicBaseUrl publicBaseUrl;
  private final ExternalAccessProperties properties;
  private final Clock clock;

  public LibraryExternalAccessReminderService(
      KnowledgeLibraryRepository libraryRepository,
      UserRepository userRepository,
      MailService mail,
      PublicBaseUrl publicBaseUrl,
      ExternalAccessProperties properties,
      Clock clock) {
    this.libraryRepository = libraryRepository;
    this.userRepository = userRepository;
    this.mail = mail;
    this.publicBaseUrl = publicBaseUrl;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * @return how many reminders this run queues
   */
  @Transactional
  public int runOnce() {
    if (properties.reminderLeadDays() == 0) {
      return 0;
    }
    Instant now = clock.instant();
    Instant until = now.plus(Duration.ofDays(properties.reminderLeadDays()));
    List<KnowledgeLibrary> due =
        libraryRepository.findAllById(libraryRepository.lockExternalAccessReminderDue(now, until));
    int queued = 0;
    for (KnowledgeLibrary library : due) {
      library.markExternalAccessReminderSent(now);
      libraryRepository.save(library);
      Optional<User> recipient = resolveRecipient(library);
      if (recipient.isEmpty()) {
        log.warn(
            "No reachable responsible person for the expiring external access release of library"
                + " {}; the release still expires on its own",
            library.getId());
        continue;
      }
      User user = recipient.get();
      mail.enqueue(
          MailTemplateKey.EXTERNAL_ACCESS_RELEASE_EXPIRING,
          Locale.GERMAN,
          user.getEmail(),
          Map.of(
              "displayName",
              user.getDisplayName() == null ? "" : user.getDisplayName(),
              "libraryName",
              library.getName(),
              "expiresAtDate",
              DATE.format(library.getExternalAccessExpiresAt().atZone(clock.getZone())),
              "actionUrl",
              publicBaseUrl.link("libraries/" + library.getId()).orElse("")));
      queued++;
    }
    return queued;
  }

  private Optional<User> resolveRecipient(KnowledgeLibrary library) {
    return findUser(library.getExternalAccessSetByUserId())
        .or(
            () ->
                library.getOwnerType() == AssetOwnerType.USER
                    ? findUser(library.getOwnerUserId())
                    : Optional.empty());
  }

  private Optional<User> findUser(UUID userId) {
    return userId == null ? Optional.empty() : userRepository.findById(userId);
  }
}
