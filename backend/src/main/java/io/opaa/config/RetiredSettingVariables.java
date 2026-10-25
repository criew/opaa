package io.opaa.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Warns at start about variables that once preset a setting the system administration now holds in
 * the database (ADR-0060, Entscheidung 1): they are no longer read, and a value still set there
 * would otherwise be lost without a word. Only warns, so an update with an old environment starts;
 * each instance warns for its own environment (ADR-0053).
 */
@Configuration
public class RetiredSettingVariables {

  private static final Logger log = LoggerFactory.getLogger(RetiredSettingVariables.class);

  private static final String CONNECTION_SETTINGS =
      "under Administration > Anbindungen > Einstellungen";

  private static final String INACTIVITY =
      "the inactivity threshold of the connections (the factory default of 90 days applies now: a"
          + " lower value set before let connections rest earlier, so the secrets of absent persons"
          + " stay usable longer now; a higher one let them rest later)";

  private static final String DELETION =
      "the deletion period of private libraries (the factory default of 30 days applies now; it is"
          + " never taken over, it only brakes: while it keeps longer and no value is set there, the"
          + " erasure of private libraries after their period pauses)";

  private static final String WARNING =
      "the warning threshold of expired connected accounts (the factory default of 10 applies now)";

  private static final String PRIVACY_SETTINGS = "under Administration > Datenschutz";

  /** Each retired variable with the setting it preset. */
  static final Map<String, String> RETIRED = retired();

  /**
   * The property each variable of {@link #RETIRED} was bound to, where one was; either name gives
   * one message, since relaxed binding finds the variable under the property name as well.
   */
  static final Map<String, String> PROPERTY_NAMES =
      Map.of(
          "OPAA_CONNECTION_INACTIVITY_THRESHOLD_DAYS", "opaa.connection.inactivity-threshold-days",
          "OPAA_CONNECTION_PRIVATE_LIBRARY_DELETION_DAYS",
              "opaa.connection.private-library-deletion-days",
          "OPAA_CONNECTION_EXPIRED_WARNING_THRESHOLD",
              "opaa.connection.expired-connections.warning-threshold");

  /** The retired retention periods, held on the page of the data protection settings. */
  static final Map<String, String> RETIRED_RETENTION = retiredRetention();

  private final Environment environment;

  public RetiredSettingVariables(Environment environment) {
    this.environment = environment;
  }

  @PostConstruct
  void warnAboutRetiredVariables() {
    foundIn(environment).forEach(log::warn);
  }

  /** One message per retired variable {@code environment} still sets, in a fixed order. */
  static List<String> foundIn(Environment environment) {
    List<String> messages = new ArrayList<>();
    RETIRED.forEach(
        (variable, setting) -> {
          String property = PROPERTY_NAMES.get(variable);
          if (environment.containsProperty(variable)
              || (property != null && environment.containsProperty(property))) {
            String named = property == null ? variable : variable + " (" + property + ")";
            messages.add(named + " is no longer read; set " + setting + " " + CONNECTION_SETTINGS);
          }
        });
    RETIRED_RETENTION.forEach(
        (variable, setting) -> {
          if (environment.containsProperty(variable)) {
            messages.add(
                variable
                    + " is no longer read; set "
                    + setting
                    + " "
                    + PRIVACY_SETTINGS
                    + "; it is never taken over, it only brakes: while it keeps longer than the"
                    + " factory default and no value is set there, the deletion it governs pauses,"
                    + " otherwise deletion follows the factory default once its period is reached");
          }
        });
    return messages;
  }

  private static Map<String, String> retiredRetention() {
    Map<String, String> retired = new LinkedHashMap<>();
    retired.put("OPAA_CHAT_AUTO_CLEANUP_ARCHIVE_AFTER_DAYS", "the periods of the chat cleanup");
    retired.put("OPAA_CHAT_AUTO_CLEANUP_DELETE_AFTER_DAYS", "the periods of the chat cleanup");
    retired.put(
        "OPAA_CHAT_LOCKED_ACCOUNT_CLEANUP_ENABLED", "the deletion of the chats of locked accounts");
    retired.put(
        "OPAA_CHAT_LOCKED_ACCOUNT_CLEANUP_DELETE_AFTER_DAYS",
        "the deletion of the chats of locked accounts");
    retired.put(
        "OPAA_AUDIT_INCIDENT_SCOPE_RETENTION_YEARS",
        "the retention of the anlassbezogene Klaerungen");
    return Collections.unmodifiableMap(retired);
  }

  private static Map<String, String> retired() {
    Map<String, String> retired = new LinkedHashMap<>();
    retired.put("OPAA_INDEXING_CONFLUENCE_FULL_SYNC_INTERVAL", "the full-sync rhythm");
    retired.put("OPAA_INDEXING_GOOGLE_DRIVE_FULL_SYNC_INTERVAL", "the full-sync rhythm");
    retired.put(
        "OPAA_INDEXING_SHAREPOINT_FULL_SYNC_INTERVAL",
        "the full-sync rhythm of SharePoint and of OneDrive, which it also covered,");
    retired.put("OPAA_INDEXING_DROPBOX_FULL_SYNC_INTERVAL", "the full-sync rhythm");
    retired.put(
        "OPAA_LIBRARY_PRIVATE_STORAGE_QUOTA_BYTES", "the storage quota of private libraries");
    retired.put("OPAA_CONNECTION_INACTIVITY_THRESHOLD_DAYS", INACTIVITY);
    retired.put("OPAA_CONNECTION_PRIVATE_LIBRARY_DELETION_DAYS", DELETION);
    retired.put("OPAA_CONNECTION_EXPIRED_WARNING_THRESHOLD", WARNING);
    return Collections.unmodifiableMap(retired);
  }
}
