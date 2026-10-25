package io.opaa.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  private static final String PRIVACY_SETTINGS = "under Administration > Datenschutz";

  /** Each retired variable with the setting it preset. */
  static final Map<String, String> RETIRED = retired();

  /** The retired retention periods, held on the page of the data protection settings. */
  static final Map<String, String> RETIRED_RETENTION = retiredRetention();

  /**
   * The retired administration values, each under its variable and its property name, with the
   * setting and the place it lives now.
   */
  static final Map<String, String> RETIRED_ADMINISTRATION_VALUES = retiredAdministrationValues();

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
          if (environment.containsProperty(variable)) {
            messages.add(
                variable + " is no longer read; set " + setting + " " + CONNECTION_SETTINGS);
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
    // The environment answers a property name for its variable too: one message per setting.
    Set<String> named = new HashSet<>();
    RETIRED_ADMINISTRATION_VALUES.forEach(
        (name, setting) -> {
          if (environment.containsProperty(name) && named.add(setting)) {
            messages.add(name + " is no longer read; set " + setting);
          }
        });
    return messages;
  }

  private static Map<String, String> retiredAdministrationValues() {
    Map<String, String> retired = new LinkedHashMap<>();
    String reminder =
        "the reminder before a release expires under Administration > Anbindungen > Externe"
            + " KI-Werkzeuge > Einstellungen";
    retired.put("OPAA_EXTERNAL_ACCESS_REMINDER_LEAD_DAYS", reminder);
    retired.put("opaa.external-access.reminder-lead-days", reminder);
    String aging = "the aging threshold under Administration > Ohne Zuständigkeit";
    retired.put("OPAA_SUCCESSION_AGING_THRESHOLD_MONTHS", aging);
    retired.put("opaa.succession.aging-threshold-months", aging);
    String plausibility =
        "the plausibility threshold in percent under Administration > Verzeichnisabgleich";
    retired.put("OPAA_DIRECTORY_SYNC_CHANGE_THRESHOLD_FRACTION", plausibility);
    retired.put("opaa.directory-sync.change-threshold-fraction", plausibility);
    String descent = "the full-sync rhythm of Nextcloud in whole days " + CONNECTION_SETTINGS;
    retired.put("OPAA_INDEXING_NEXTCLOUD_FULL_DESCENT_INTERVAL", descent);
    retired.put("opaa.indexing.nextcloud.full-descent-interval", descent);
    return Collections.unmodifiableMap(retired);
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
    return Collections.unmodifiableMap(retired);
  }
}
