package io.opaa.installation;

import io.opaa.llm.ModelAddressPolicy;
import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailSettingsSnapshot;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Checks the stored model endpoints of every role and the stored mail server once after start, as
 * stored targets, so their names are guarded against DNS rebinding although calls at run time are
 * not checked (ADR-0063). Runs on a thread of its own and only warns; each instance does it for
 * itself (ADR-0053).
 */
@Component
class StoredTargetWarmup {

  private static final Logger log = LoggerFactory.getLogger(StoredTargetWarmup.class);

  /** The default SMTP port JavaMail connects to when none is stored. */
  private static final int SMTP_DEFAULT_PORT = 25;

  private static final String STORED_MODEL_ENDPOINTS =
      "SELECT base_url FROM llm_models"
          + " UNION SELECT base_url FROM embedding_model"
          + " UNION SELECT base_url FROM rerank_model"
          + " UNION SELECT base_url FROM transcription_model";

  private final JdbcTemplate jdbc;
  private final ModelAddressPolicy modelAddresses;
  private final MailSettingsService mailSettings;
  private final InstallationMailTargets mailTargets;

  StoredTargetWarmup(
      JdbcTemplate jdbc,
      ModelAddressPolicy modelAddresses,
      MailSettingsService mailSettings,
      InstallationMailTargets mailTargets) {
    this.jdbc = jdbc;
    this.modelAddresses = modelAddresses;
    this.mailSettings = mailSettings;
    this.mailTargets = mailTargets;
  }

  /** Starts {@link #warmUp} without holding the start; its failures stay warnings. */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    Thread.ofVirtual().name("stored-target-warmup").start(this::warmUp);
  }

  void warmUp() {
    try {
      warmUpModels();
    } catch (RuntimeException e) {
      log.warn("Stored model endpoints could not be checked at start: {}", e.toString());
    }
    try {
      warmUpMail();
    } catch (RuntimeException e) {
      log.warn("The stored mail server could not be checked at start: {}", e.toString());
    }
  }

  private void warmUpModels() {
    List<String> endpoints = jdbc.queryForList(STORED_MODEL_ENDPOINTS, String.class);
    long refused =
        endpoints.stream()
            .filter(url -> url != null && !url.isBlank())
            .filter(url -> modelAddresses.blockedReason(url) != null)
            .count();
    if (refused > 0) {
      log.warn(
          "{} stored model endpoint(s) did not pass the address check at start and are not"
              + " guarded against DNS rebinding",
          refused);
    }
  }

  private void warmUpMail() {
    MailSettingsSnapshot snapshot = mailSettings.snapshot();
    if (snapshot == null || !snapshot.sendable()) {
      return;
    }
    int port = snapshot.port() == null ? SMTP_DEFAULT_PORT : snapshot.port();
    if (!mailTargets.guardStoredServer(snapshot.host(), port)) {
      log.info(
          "The stored mail server is internal or not admitted for the installation and is not"
              + " guarded against DNS rebinding");
    }
  }
}
