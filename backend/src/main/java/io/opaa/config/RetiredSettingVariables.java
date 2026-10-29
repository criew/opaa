package io.opaa.config;

import io.opaa.llm.RetiredModelRoleVariables;
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
import org.springframework.core.env.Profiles;

/**
 * Warns at start about variables that once preset a setting the system administration or each
 * library now holds in the database, or a value the installation now sets (ADR-0060, Entscheidungen
 * 1 and 8 to 10): they are no longer read, outside the {@code dev} mode or at all, and a value
 * still set there would otherwise be lost without a word. Only warns, so an update with an old
 * environment starts; each instance warns for its own environment (ADR-0053).
 */
@Configuration
public class RetiredSettingVariables {

  private static final Logger log = LoggerFactory.getLogger(RetiredSettingVariables.class);

  private static final String CONNECTION_SETTINGS =
      "under Administration > Anbindungen > Einstellungen";

  private static final String PRIVACY_SETTINGS = "under Administration > Datenschutz";

  private static final String INACTIVITY =
      "the inactivity threshold of the connections (the factory default of 90 days applies now: a"
          + " lower value set before let connections rest earlier, so the secrets of absent persons"
          + " stay usable longer now; a higher one let them rest later) "
          + CONNECTION_SETTINGS;

  private static final String DELETION =
      "the deletion period of private libraries (the factory default of 30 days applies now; it is"
          + " never taken over, it only brakes: while it keeps longer and no value is set there, the"
          + " erasure of private libraries after their period pauses) "
          + CONNECTION_SETTINGS;

  private static final String WARNING =
      "the warning threshold of expired connected accounts (the factory default of 10 applies now) "
          + CONNECTION_SETTINGS;

  /** Each retired variable with the setting it preset and the place that setting lives now. */
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

  /**
   * The shared model address and key, read in the {@code dev} mode only (by its own installation);
   * outside it they are named like a retired variable.
   */
  static final List<String> DEV_MODE_ONLY = List.of("OPAA_OPENAI_BASE_URL", "OPAA_OPENAI_API_KEY");

  private static final String MODELS =
      "the chat model in the installation, then in the model administration, and the embedding"
          + " model in the installation, then under Administration > Modelle > Einbettung";

  /** The retired retention periods, held on the page of the data protection settings. */
  static final Map<String, String> RETIRED_RETENTION = retiredRetention();

  /**
   * The retired administration values, each under its variable and its property name, with the
   * setting and the place it lives now.
   */
  static final Map<String, String> RETIRED_ADMINISTRATION_VALUES = retiredAdministrationValues();

  /**
   * The retired installation-wide defaults of a feed library's own settings, each under its
   * variable and its property name; never taken over, every library starts from the factory
   * default.
   */
  static final Map<String, String> RETIRED_PER_LIBRARY = retiredPerLibrary();

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
            messages.add(named + " is no longer read; set " + setting);
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
    Set<String> perLibrary = new HashSet<>();
    RETIRED_PER_LIBRARY.forEach(
        (name, setting) -> {
          if (environment.containsProperty(name) && perLibrary.add(setting)) {
            messages.add(name + " is no longer read and not taken over; set " + setting);
          }
        });
    if (!environment.acceptsProfiles(Profiles.of("dev"))) {
      DEV_MODE_ONLY.stream()
          .filter(environment::containsProperty)
          .forEach(
              variable -> messages.add(variable + " is read in the dev mode only; set " + MODELS));
    }
    RetiredModelRoleVariables.setIn(environment, RetiredModelRoleVariables.RERANK)
        .forEach(variable -> messages.add(modelRoleMessage(variable, "Reranking")));
    RetiredModelRoleVariables.setIn(environment, RetiredModelRoleVariables.TRANSCRIPTION)
        .forEach(variable -> messages.add(modelRoleMessage(variable, "Spracheingabe")));
    return messages;
  }

  /** The model roles are never taken over: switched on before, the role is off until set there. */
  private static String modelRoleMessage(String variable, String tab) {
    return variable
        + " is no longer read and not taken over; set the model role under Administration >"
        + " Modelle > "
        + tab
        + " - until then the role is off";
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
        "the plausibility threshold in percent under Administration > Verzeichnisabgleich; until"
            + " then 30 percent applies, so if it was stricter, restart with"
            + " OPAA_DIRECTORY_SYNC_SCHEDULE_ENABLED=false, set the threshold, then switch the"
            + " schedule on again - a lock ends the person's connected accounts for good";
    retired.put("OPAA_DIRECTORY_SYNC_CHANGE_THRESHOLD_FRACTION", plausibility);
    retired.put("opaa.directory-sync.change-threshold-fraction", plausibility);
    String descent = "the full-sync rhythm of Nextcloud in whole days " + CONNECTION_SETTINGS;
    retired.put("OPAA_INDEXING_NEXTCLOUD_FULL_DESCENT_INTERVAL", descent);
    retired.put("opaa.indexing.nextcloud.full-descent-interval", descent);
    return Collections.unmodifiableMap(retired);
  }

  private static Map<String, String> retiredPerLibrary() {
    Map<String, String> retired = new LinkedHashMap<>();
    String place = " of each RSS library in its source configuration (Reiter Quelle, Umfang)";
    String selector =
        "the content selector"
            + place
            + " - until then the factory default main, article, [role=main] applies";
    retired.put("OPAA_INDEXING_RSS_MAIN_CONTENT_SELECTOR", selector);
    retired.put("opaa.indexing.rss.main-content-selector", selector);
    String profile =
        "the attachment profile" + place + " - until then the factory default GENERIC applies";
    retired.put("OPAA_INDEXING_RSS_ATTACHMENT_PROFILE", profile);
    retired.put("opaa.indexing.rss.attachment-profile", profile);
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
    String rhythm = "the full-sync rhythm " + CONNECTION_SETTINGS;
    retired.put("OPAA_INDEXING_CONFLUENCE_FULL_SYNC_INTERVAL", rhythm);
    retired.put("OPAA_INDEXING_GOOGLE_DRIVE_FULL_SYNC_INTERVAL", rhythm);
    retired.put(
        "OPAA_INDEXING_SHAREPOINT_FULL_SYNC_INTERVAL",
        "the full-sync rhythm of SharePoint and of OneDrive, which it also covered, "
            + CONNECTION_SETTINGS);
    retired.put("OPAA_INDEXING_DROPBOX_FULL_SYNC_INTERVAL", rhythm);
    retired.put(
        "OPAA_LIBRARY_PRIVATE_STORAGE_QUOTA_BYTES",
        "the storage quota of private libraries " + CONNECTION_SETTINGS);
    retired.put("OPAA_CONNECTION_INACTIVITY_THRESHOLD_DAYS", INACTIVITY);
    retired.put("OPAA_CONNECTION_PRIVATE_LIBRARY_DELETION_DAYS", DELETION);
    retired.put("OPAA_CONNECTION_EXPIRED_WARNING_THRESHOLD", WARNING);
    String account = "the first account in the installation, then in the user administration";
    retired.put("OPAA_INITIAL_ADMIN_EMAIL", account);
    retired.put("OPAA_INITIAL_ADMIN_PASSWORD", account);
    String provider =
        "the identity provider in the installation, then in the Anbieterverwaltung; an internal"
            + " one needs its hosts in OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST";
    for (String variable :
        List.of(
            "OPAA_OIDC_ISSUER_URI",
            "OPAA_OIDC_CLIENT_ID",
            "OPAA_OIDC_JWK_SET_URI",
            "OPAA_OIDC_AUTHORITY",
            "OPAA_OIDC_BOOTSTRAP")) {
      retired.put(variable, provider);
    }
    String chatModel = "the chat model in the installation, then in the model administration";
    for (String variable :
        List.of(
            "OPAA_OPENAI_CHAT_BASE_URL",
            "OPAA_OPENAI_CHAT_API_KEY",
            "OPAA_OPENAI_CHAT_MODEL",
            "OPAA_OPENAI_CHAT_TEMPERATURE",
            "OPAA_OPENAI_CHAT_MAX_TOKENS",
            "OPAA_AI_CHAT_PROVIDER",
            "OPAA_OLLAMA_CHAT_MODEL")) {
      retired.put(variable, chatModel);
    }
    String embeddingModel =
        "the embedding model in the installation, fixed afterwards by its vector size";
    for (String variable :
        List.of(
            "OPAA_OPENAI_EMBEDDING_BASE_URL",
            "OPAA_OPENAI_EMBEDDING_API_KEY",
            "OPAA_OPENAI_EMBEDDING_MODEL",
            "OPAA_OPENAI_EMBEDDING_MAX_INPUT_TOKENS",
            "OPAA_PGVECTOR_DIMENSIONS",
            "OPAA_OLLAMA_EMBEDDING_MODEL",
            "OPAA_AI_EMBEDDING_PROVIDER")) {
      retired.put(variable, embeddingModel);
    }
    retired.put("OPAA_OLLAMA_BASE_URL", embeddingModel + ", and " + chatModel);
    return Collections.unmodifiableMap(retired);
  }
}
