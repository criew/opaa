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

  /** Each retired variable with the setting it preset. */
  static final Map<String, String> RETIRED = retired();

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
    return messages;
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
