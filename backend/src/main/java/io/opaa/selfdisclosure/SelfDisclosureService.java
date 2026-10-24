package io.opaa.selfdisclosure;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.NotificationType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.RecentLoginPolicy;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.DatabaseClock;
import io.opaa.common.NotFoundException;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.compliance.DataFlow;
import io.opaa.compliance.InstanceDisclosureAssembler;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.mail.MailService;
import io.opaa.mail.MailTemplateKey;
import io.opaa.notification.NotificationService;
import io.opaa.observability.SelfDisclosureMetrics;
import io.opaa.observability.SelfDisclosureMetrics.Refusal;
import io.opaa.selfdisclosure.SelfDisclosureAbort.AbortCause;
import io.opaa.selfdisclosure.SelfDisclosureStatus.Blocked;
import io.opaa.selfdisclosure.SelfDisclosureUsageRepository.Usage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The self-disclosure of the signed-in person (docs/features/security-and-compliance.md,
 * "Selbstauskunft"): status, a one-time ticket issued only after a recent sign-in, and the export
 * the browser downloads with it. The person is always the caller or the holder of the ticket, never
 * a parameter. Limits are decided in the database (ADR-0053, Entscheidung 12), the number of
 * exports streaming at once per instance by {@link SelfDisclosureRunRegistry}.
 */
@Service
@DataFlow("mail")
public class SelfDisclosureService {

  private static final Logger log = LoggerFactory.getLogger(SelfDisclosureService.class);

  static final String DISABLED_MESSAGE =
      "Die Selbstauskunft ist in dieser Installation derzeit abgeschaltet. Ihr Recht auf Auskunft"
          + " nach Art. 15 DSGVO bleibt davon unberührt: Sie können die Auskunft bei Ihrer Behörde"
          + " beantragen, in der Regel über die oder den Datenschutzbeauftragten.";

  /** How long a ticket is valid; the same as {@link SelfDisclosureUsageRepository}. */
  public static final int TICKET_SECONDS = 60;

  /** How long a client waits after a refusal for load. */
  static final long INSTANCE_LIMIT_RETRY_AFTER_SECONDS = 60;

  private static final DateTimeFormatter OCCURRED_AT =
      DateTimeFormatter.ofPattern("'am' dd.MM.yyyy 'um' HH:mm 'Uhr'", Locale.GERMANY);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SelfDisclosureSettingsService settings;
  private final SelfDisclosureUsageRepository usage;
  private final SelfDisclosureRunRegistry registry;
  private final SelfDisclosureProperties properties;
  private final SelfDisclosureExporters exporters;
  private final AuditEventRecorder audit;
  private final NotificationService notifications;
  private final MailService mail;
  private final UserRepository users;
  private final RecentLoginPolicy recentLogin;
  private final SelfDisclosureMetrics metrics;
  private final DatabaseClock databaseClock;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final String version;
  private final JsonMapper json = JsonMapper.builder().build();

  public SelfDisclosureService(
      SelfDisclosureSettingsService settings,
      SelfDisclosureUsageRepository usage,
      SelfDisclosureRunRegistry registry,
      SelfDisclosureProperties properties,
      SelfDisclosureExporters exporters,
      AuditEventRecorder audit,
      NotificationService notifications,
      MailService mail,
      UserRepository users,
      RecentLoginPolicy recentLogin,
      SelfDisclosureMetrics metrics,
      DatabaseClock databaseClock,
      Clock clock,
      PlatformTransactionManager transactionManager,
      ObjectProvider<BuildProperties> buildProperties) {
    this.settings = settings;
    this.usage = usage;
    this.registry = registry;
    this.properties = properties;
    this.exporters = exporters;
    this.audit = audit;
    this.notifications = notifications;
    this.mail = mail;
    this.users = users;
    this.recentLogin = recentLogin;
    this.metrics = metrics;
    this.databaseClock = databaseClock;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
    String built =
        buildProperties.stream().map(BuildProperties::getVersion).findFirst().orElse(null);
    this.version =
        built == null || built.isBlank() ? InstanceDisclosureAssembler.DEVELOPMENT_VERSION : built;
  }

  /** The status of the caller's own self-disclosure. */
  public SelfDisclosureStatus status(CurrentUser caller) {
    requireSignedInPerson(caller);
    SelfDisclosureSettings current = settings.current();
    Instant now = databaseClock.now();
    LocalDate today = dayOf(now);
    Optional<Usage> row = usage.find(caller.id());
    int completed = row.map(u -> u.completedOn(today)).orElse(0);
    int starts = row.map(u -> u.startsOn(today)).orElse(0);
    boolean running = row.map(Usage::running).orElse(false);
    int remainingExports = Math.max(0, current.dailyLimit() - completed);
    int remainingStarts = Math.max(0, current.maxStarts() - starts);
    Blocked blocked = blocked(current, running, remainingExports, remainingStarts);
    Instant nextPossibleAt = null;
    if (blocked == Blocked.RUNNING_EXPORT) {
      nextPossibleAt = row.map(Usage::leaseUntil).orElse(null);
    } else if (blocked == Blocked.DAILY_LIMIT || blocked == Blocked.STARTS_EXHAUSTED) {
      nextPossibleAt = today.plusDays(1).atStartOfDay(zone()).toInstant();
    }
    String lastResult = row.map(Usage::effectiveLastResult).orElse(null);
    return new SelfDisclosureStatus(
        current.enabled(),
        current.dailyLimit(),
        completed,
        starts,
        remainingExports,
        remainingStarts,
        running,
        blocked,
        nextPossibleAt,
        row.map(Usage::lastCompletedAt).orElse(null),
        lastResult,
        "ABORTED".equals(lastResult) && blocked == null);
  }

  /**
   * Issues the one-time ticket of the caller's next export: only with a recent sign-in, only while
   * the self-disclosure is on and the day leaves an export and a start. Valid for 60 seconds and
   * once; a newer ticket replaces an older one. Only its hash is stored.
   */
  public String issueTicket(CurrentUser caller, Authentication authentication) {
    requireSignedInPerson(caller);
    try {
      recentLogin.requireRecentLogin(authentication);
    } catch (AccessDeniedException refused) {
      metrics.refused(Refusal.RECENT_LOGIN_REQUIRED);
      throw refused;
    }
    SelfDisclosureSettings current = settings.current();
    if (!current.enabled()) {
      metrics.refused(Refusal.DISABLED);
      throw disabled();
    }
    byte[] secret = new byte[32];
    RANDOM.nextBytes(secret);
    String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    LocalDate today = dayOf(databaseClock.now());
    if (!usage.issueTicket(caller.id(), today, hash(ticket), current.dailyLimit())) {
      throw limitRefusal(current, usage.find(caller.id()).orElseThrow(), today);
    }
    return ticket;
  }

  /**
   * Redeems {@code ticket} and begins the export: takes a slot of this instance, reserves the
   * export in the database, counts the start, and writes the start event, the notification and the
   * mail in one transaction. Throws the refusal otherwise; nothing was read then.
   */
  public Run start(String ticket) {
    if (!registry.tryAcquire()) {
      metrics.refused(Refusal.INSTANCE_LIMIT);
      throw new ServiceUnavailableException(
          "Gerade laufen zu viele Selbstauskünfte. Bitte versuchen Sie es in einer Minute erneut.",
          INSTANCE_LIMIT_RETRY_AFTER_SECONDS);
    }
    try {
      Run run = transactions.execute(status -> redeemAndAnnounce(ticket));
      metrics.started();
      return run;
    } catch (RuntimeException refused) {
      registry.release();
      throw refused;
    }
  }

  private Run redeemAndAnnounce(String ticket) {
    byte[] ticketHash = hash(ticket == null ? "" : ticket);
    UUID runId = UUID.randomUUID();
    Instant startedAt = databaseClock.now();
    LocalDate today = dayOf(startedAt);
    UUID accountId =
        usage
            .redeem(ticketHash, runId, today)
            .orElseThrow(() -> redemptionRefusal(ticketHash, today));
    User user =
        users
            .findById(accountId)
            .orElseThrow(() -> new IllegalStateException("account of a ticket vanished"));
    List<String> categories = exporters.categoryIds();
    audit.recordOwnAccountAction(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actor(accountId)
            .type(AuditEventType.SELF_DISCLOSURE_EXPORT_STARTED)
            .object(
                AuditObjectType.USER_ACCOUNT,
                audit.pseudonymFor(accountId, user.getOrganizationId()),
                null)
            .after(Map.of("categories", categories))
            .outcome(AuditOutcome.SUCCESS)
            .correlationRef(runId.toString())
            .build());
    String occurredAt = OCCURRED_AT.format(startedAt.atZone(zone()));
    notifications.notify(
        user.getOrganizationId(),
        accountId,
        NotificationType.SELF_DISCLOSURE_STARTED,
        null,
        null,
        "Selbstauskunft gestartet",
        capitalized(occurredAt)
            + " wurde eine Selbstauskunft Ihrer Daten gestartet. Falls Sie das nicht waren,"
            + " ändern Sie Ihr Passwort und wenden Sie sich an Ihre Systemverwaltung.");
    mail.enqueue(
        MailTemplateKey.SELF_DISCLOSURE_STARTED,
        Locale.GERMAN,
        user.getEmail(),
        Map.of(
            "displayName",
            user.getDisplayName() == null ? "" : user.getDisplayName(),
            "occurredAtHuman",
            occurredAt));
    return new Run(accountId, user.getOrganizationId(), runId, today, startedAt, System.nanoTime());
  }

  /**
   * Streams the ZIP of {@code run} into {@code out} and ends it: counted and recorded as completed
   * once the central directory is written and flushed, otherwise recorded as aborted without
   * consuming anything. The outcome tells the caller whether to end an aborted one visibly on the
   * connection; the slot of this instance is released in every case.
   */
  public Outcome stream(Run run, OutputStream out) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      registry.release();
      throw new IllegalStateException("The self-disclosure must stream outside a transaction");
    }
    SelfDisclosureRunControl control =
        registry.watch(
            run.startedNanos() + properties.maxDuration().toNanos(),
            () -> usage.renew(run.accountId(), run.runId()));
    SelfDisclosureArchive archive = new SelfDisclosureArchive(out, zone(), control::check);
    SelfDisclosureSubject subject =
        new SelfDisclosureSubject(run.accountId(), run.organizationId());
    try {
      for (SelfDisclosureExporter exporter : exporters.all()) {
        control.check();
        archive.category(exporter.category(), sink -> exporter.export(subject, sink));
      }
      control.check();
      Instant generatedAt = clock.instant();
      archive.finish(
          manifestJson(generatedAt, archive),
          SelfDisclosureOverviewRenderer.render(
                  generatedAt,
                  zone(),
                  archive.results(),
                  exporters.facts(),
                  exporters.missingCategoryTitles())
              .getBytes(StandardCharsets.UTF_8));
      if (!usage.complete(run.accountId(), run.runId())) {
        throw new SelfDisclosureAbort(AbortCause.ERROR, "reservation lost before completion");
      }
      finish(run, archive, "COMPLETED", null);
      metrics.completed(elapsed(run), archive.bytes());
      return Outcome.DELIVERED;
    } catch (Exception | Error failure) {
      AbortCause cause = causeOf(failure);
      if (cause == AbortCause.ERROR) {
        log.error("Self-disclosure export {} failed", run.runId(), failure);
      } else {
        log.info("Self-disclosure export {} aborted: {}", run.runId(), cause);
      }
      try {
        usage.abort(run.accountId(), run.runId());
        finish(run, archive, "ABORTED", cause);
      } catch (RuntimeException recording) {
        log.error(
            "Could not record the abort of self-disclosure export {}", run.runId(), recording);
      }
      metrics.aborted(cause.name(), elapsed(run), archive.bytes());
      return cause == AbortCause.CLIENT_DISCONNECTED
          ? Outcome.CLIENT_DISCONNECTED
          : Outcome.ABORTED_BY_SERVER;
    } finally {
      registry.unwatch(control);
      registry.release();
    }
  }

  private void finish(Run run, SelfDisclosureArchive archive, String result, AbortCause cause) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("result", result);
    if (cause != null) {
      after.put("abortCause", cause.name());
    }
    after.put("bytes", archive.bytes());
    after.put("sha256", archive.sha256());
    after.put("categories", exporters.categoryIds());
    transactions.executeWithoutResult(
        status ->
            audit.recordOwnAccountAction(
                AuditEvent.builder()
                    .organizationId(run.organizationId())
                    .actor(run.accountId())
                    .type(AuditEventType.SELF_DISCLOSURE_EXPORT_FINISHED)
                    .object(
                        AuditObjectType.USER_ACCOUNT,
                        audit.pseudonymFor(run.accountId(), run.organizationId()),
                        null)
                    .after(after)
                    .outcome(cause == null ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
                    .correlationRef(run.runId().toString())
                    .build()));
  }

  private byte[] manifestJson(Instant generatedAt, SelfDisclosureArchive archive) {
    List<Map<String, Object>> categories = new ArrayList<>();
    for (SelfDisclosureArchive.CategoryResult result : archive.results()) {
      Map<String, Object> category = new LinkedHashMap<>();
      category.put("id", result.category().id());
      category.put("title", result.category().title());
      category.put("file", result.fileName());
      category.put("records", result.records());
      categories.add(category);
    }
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("generatedAt", generatedAt.toString());
    manifest.put("opaaVersion", version);
    manifest.put("categories", categories);
    return json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
  }

  static AbortCause causeOf(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof SelfDisclosureAbort abort) {
        return abort.abortCause();
      }
    }
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof IOException) {
        return AbortCause.CLIENT_DISCONNECTED;
      }
    }
    return AbortCause.ERROR;
  }

  private Blocked blocked(
      SelfDisclosureSettings current, boolean running, int remainingExports, int remainingStarts) {
    if (!current.enabled()) {
      return Blocked.DISABLED;
    }
    if (running) {
      return Blocked.RUNNING_EXPORT;
    }
    if (remainingExports == 0) {
      return Blocked.DAILY_LIMIT;
    }
    if (remainingStarts == 0) {
      return Blocked.STARTS_EXHAUSTED;
    }
    return null;
  }

  private RuntimeException redemptionRefusal(byte[] ticketHash, LocalDate today) {
    Optional<UUID> holder = usage.holderOfValidTicket(ticketHash);
    if (holder.isEmpty()) {
      return new NotFoundException(
          "Der Download ist abgelaufen oder wurde bereits verwendet. Bitte starten Sie die"
              + " Selbstauskunft erneut.",
          "SELF_DISCLOSURE_TICKET_INVALID");
    }
    SelfDisclosureSettings current = settings.current();
    if (!current.enabled()) {
      metrics.refused(Refusal.DISABLED);
      return disabled();
    }
    return limitRefusal(current, usage.find(holder.get()).orElseThrow(), today);
  }

  private TooManyRequestsException limitRefusal(
      SelfDisclosureSettings current, Usage row, LocalDate today) {
    long untilTomorrow =
        Math.max(
            1,
            Duration.between(row.now(), today.plusDays(1).atStartOfDay(zone()).toInstant())
                .toSeconds());
    if (row.running()) {
      metrics.refused(Refusal.RUNNING_EXPORT);
      return new TooManyRequestsException(
          "Es läuft bereits eine Selbstauskunft für Sie. Bitte warten Sie, bis sie beendet ist.",
          Math.max(1, Duration.between(row.now(), row.leaseUntil()).toSeconds()),
          "SELF_DISCLOSURE_RUNNING");
    }
    if (row.completedOn(today) >= current.dailyLimit()) {
      metrics.refused(Refusal.DAILY_LIMIT);
      return new TooManyRequestsException(
          "Sie haben Ihre Selbstauskunft heute so oft heruntergeladen, wie es die Tagesgrenze"
              + " erlaubt. Morgen ist sie wieder möglich.",
          untilTomorrow,
          "SELF_DISCLOSURE_DAILY_LIMIT");
    }
    metrics.refused(Refusal.STARTS_EXHAUSTED);
    return new TooManyRequestsException(
        "Die Starts der Selbstauskunft für heute sind erschöpft. Morgen ist sie wieder möglich.",
        untilTomorrow,
        "SELF_DISCLOSURE_STARTS_EXHAUSTED");
  }

  private static AccessDeniedException disabled() {
    return new AccessDeniedException(DISABLED_MESSAGE, "SELF_DISCLOSURE_DISABLED");
  }

  /**
   * The self-disclosure is reachable only with a sign-in at the interface, never through the access
   * token of an external AI tool.
   */
  private static void requireSignedInPerson(CurrentUser caller) {
    if (caller.accessTokenId() != null) {
      throw new AccessDeniedException(
          "Die Selbstauskunft ist nur nach Anmeldung an der Oberfläche möglich.",
          "SELF_DISCLOSURE_SIGN_IN_REQUIRED");
    }
  }

  private LocalDate dayOf(Instant instant) {
    return instant.atZone(zone()).toLocalDate();
  }

  private ZoneId zone() {
    return clock.getZone();
  }

  private static Duration elapsed(Run run) {
    return Duration.ofNanos(System.nanoTime() - run.startedNanos());
  }

  private static String capitalized(String text) {
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  static byte[] hash(String ticket) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every JRE", e);
    }
  }

  /** How {@link #stream} ended. */
  public enum Outcome {
    /** Completed and counted. */
    DELIVERED,
    /** The client went away; nobody listens any more. */
    CLIENT_DISCONNECTED,
    /** Ended by the server (deadline, shutdown, lost reservation, failure). */
    ABORTED_BY_SERVER
  }

  /**
   * A begun export.
   *
   * @param day the calendar day it counts for, the day it began
   * @param startedAt when it began, by the database clock
   */
  public record Run(
      UUID accountId,
      UUID organizationId,
      UUID runId,
      LocalDate day,
      Instant startedAt,
      long startedNanos) {

    /** The file name of the download, dated in the zone of the installation. */
    public String fileName(ZoneId zone) {
      return "opaa-selbstauskunft-" + startedAt.atZone(zone).toLocalDate() + ".zip";
    }
  }

  /** The zone of the installation, for the file name. */
  public ZoneId installationZone() {
    return zone();
  }
}
