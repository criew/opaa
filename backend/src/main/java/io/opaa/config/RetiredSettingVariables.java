package io.opaa.config;

import io.opaa.llm.RetiredModelRoleVariables;
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
 * the database, or a value the installation now sets (ADR-0060, Entscheidungen 1 and 8 to 10): they
 * are no longer read, and a value still set there would otherwise be lost without a word. Only
 * warns, so an update with an old environment starts; each instance warns for its own environment
 * (ADR-0053).
 */
@Configuration
public class RetiredSettingVariables {

  private static final Logger log = LoggerFactory.getLogger(RetiredSettingVariables.class);

  private static final String CONNECTION_SETTINGS =
      "under Administration > Anbindungen > Einstellungen";

  private static final String PRIVACY_SETTINGS = "under Administration > Datenschutz";

  /** Each retired variable with the setting it preset and the place that setting lives now. */
  static final Map<String, String> RETIRED = retired();

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
          if (environment.containsProperty(variable)) {
            messages.add(variable + " is no longer read; set " + setting);
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
