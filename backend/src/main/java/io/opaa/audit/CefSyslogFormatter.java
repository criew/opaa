package io.opaa.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Formats the SIEM export as RFC 5424 syslog messages with a CEF body (ADR-0055, Entscheidung 4).
 * One message per audit entry, carrying its event id as {@code externalId} so that a receiver can
 * drop a duplicate after a delivery that was sent but not confirmed; one gap message for entries
 * that were removed before they could be sent.
 */
final class CefSyslogFormatter {

  /** Syslog facility {@code authpriv} (10); severity notice for success, warning otherwise. */
  private static final int FACILITY = 10;

  static final String GAP_EVENT = "AUDIT_EXPORT_GAP";

  private final String hostname;
  private final String version;

  CefSyslogFormatter(String hostname, String version) {
    this.hostname = hostname == null || hostname.isBlank() ? "-" : hostname.replace(' ', '_');
    this.version = version == null || version.isBlank() ? "0.0.0-dev" : version;
  }

  String format(AuditExportEntry entry) {
    boolean success = "SUCCESS".equals(entry.outcome());
    StringBuilder extension = new StringBuilder();
    append(extension, "rt", Long.toString(entry.recordedAt().toEpochMilli()));
    append(extension, "externalId", entry.eventId().toString());
    append(extension, "outcome", entry.outcome());
    append(extension, "cat", "audit");
    append(extension, "cs1Label", "organizationId");
    append(extension, "cs1", entry.organizationId().toString());
    append(extension, "cs2Label", "objectType");
    append(extension, "cs2", entry.objectType());
    if (entry.objectId() != null) {
      append(extension, "cs3Label", "objectId");
      append(extension, "cs3", entry.objectId());
    }
    if (entry.objectLabel() != null) {
      append(extension, "cs4Label", "objectLabel");
      append(extension, "cs4", entry.objectLabel());
    }
    return message(entry.recordedAt(), success ? 5 : 4, entry.eventType(), success ? 3 : 6)
        + extension;
  }

  /**
   * The note that the entries recorded in {@code [from, to)} were not sent: {@code reason} is
   * {@code retention} when the retention removed them first, {@code disabled} for a pause of the
   * export.
   */
  String formatGap(Instant from, Instant to, Instant now, String reason) {
    StringBuilder extension = new StringBuilder();
    append(extension, "rt", Long.toString(now.toEpochMilli()));
    append(
        extension,
        "externalId",
        UUID.nameUUIDFromBytes((from + "/" + to).getBytes(StandardCharsets.UTF_8)).toString());
    append(extension, "start", Long.toString(from.toEpochMilli()));
    append(extension, "end", Long.toString(to.toEpochMilli()));
    append(extension, "cat", "audit");
    append(extension, "reason", reason);
    append(
        extension,
        "msg",
        "disabled".equals(reason)
            ? "The export was switched off; entries of this period are not exported"
            : "Entries of the audit log recorded in this period were removed before being exported");
    return message(now, 4, GAP_EVENT, 7) + extension;
  }

  private String message(Instant time, int syslogSeverity, String eventType, int cefSeverity) {
    return "<"
        + (FACILITY * 8 + syslogSeverity)
        + ">1 "
        + DateTimeFormatter.ISO_INSTANT.format(time.truncatedTo(ChronoUnit.MICROS))
        + " "
        + hostname
        + " opaa - AUDIT - CEF:0|OPAA|OPAA|"
        + header(version)
        + "|"
        + header(eventType)
        + "|"
        + header(eventType)
        + "|"
        + cefSeverity
        + "|";
  }

  private static void append(StringBuilder extension, String key, String value) {
    if (!extension.isEmpty()) {
      extension.append(' ');
    }
    extension.append(key).append('=').append(extensionValue(value));
  }

  /**
   * Control characters (C0, C1, line and paragraph separators) other than CR and LF become spaces:
   * a freely chosen name must not split or garble a message.
   */
  private static final Pattern CONTROL =
      Pattern.compile("[\\x00-\\x09\\x0B\\x0C\\x0E-\\x1F\\x7F-\\x9F\\u2028\\u2029]");

  /** CEF header fields escape backslash and pipe; line breaks would end the message. */
  static String header(String value) {
    return CONTROL
        .matcher(value)
        .replaceAll(" ")
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("\r", " ")
        .replace("\n", " ");
  }

  /** CEF extension values escape backslash and equals sign and encode line breaks. */
  static String extensionValue(String value) {
    return CONTROL
        .matcher(value)
        .replaceAll(" ")
        .replace("\\", "\\\\")
        .replace("=", "\\=")
        .replace("\r", "\\r")
        .replace("\n", "\\n");
  }
}
