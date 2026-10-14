package io.opaa.connection;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.outbound.OutboundProxy;
import io.opaa.outbound.ProxyAddress;
import io.opaa.outbound.ProxyExclusions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the house-wide outbound proxy, system administration only. Every change is the
 * governance event {@code OUTBOUND_PROXY_CHANGED} with both states; the route changes only after
 * the change committed.
 */
@Service
public class OutboundProxyAdministration {

  private static final String ADMIN_ONLY =
      "Nur die Systemverwaltung darf den Ausgangsproxy einsehen und ändern";

  static final int MAX_PROXY_URL_LENGTH = 300;
  static final int MAX_NO_PROXY_ENTRIES = 100;
  static final int MAX_NO_PROXY_ENTRY_LENGTH = 253;

  static final String INVALID = "INVALID";
  static final String TOO_LONG = "TOO_LONG";
  static final String TOO_MANY = "TOO_MANY";

  static final UUID SETTINGS_OBJECT_ID =
      UUID.nameUUIDFromBytes("outbound_proxy_settings".getBytes(StandardCharsets.UTF_8));

  private final OutboundProxy outboundProxy;
  private final AuditEventRecorder auditEventRecorder;

  OutboundProxyAdministration(OutboundProxy outboundProxy, AuditEventRecorder auditEventRecorder) {
    this.outboundProxy = outboundProxy;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Transactional(readOnly = true)
  public OutboundProxy.Settings read(CurrentUser actor) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    return outboundProxy.read();
  }

  /**
   * Stores the proxy and the exclusion list.
   *
   * @param proxyUrl {@code http://host:port} or {@code host:port}; {@code null} or blank for none
   * @param noProxy host names, IP literals and CIDR ranges of IP literals
   * @throws FieldValidationException naming {@code proxyUrl} and/or {@code noProxy}
   */
  @Transactional
  public OutboundProxy.Settings change(CurrentUser actor, String proxyUrl, List<String> noProxy) {
    actor.requireSystemAdmin(ADMIN_ONLY);
    List<FieldError> errors = new ArrayList<>();
    ProxyAddress proxy = parseProxy(proxyUrl, errors);
    List<String> entries = ProxyExclusions.normalize(noProxy);
    validateExclusions(noProxy, entries, errors);
    if (!errors.isEmpty()) {
      throw new FieldValidationException(
          "Die Einstellungen des Ausgangsproxys sind ungültig.", errors);
    }
    OutboundProxy.Settings before = outboundProxy.read();
    OutboundProxy.Settings after = outboundProxy.change(proxy, entries);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(actor.organizationId())
            .actor(actor.id())
            .type(AuditEventType.OUTBOUND_PROXY_CHANGED)
            .object(AuditObjectType.SYSTEM_SETTING, SETTINGS_OBJECT_ID, "Ausgangsproxy")
            .before(payload(before))
            .after(payload(after))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return after;
  }

  private static ProxyAddress parseProxy(String proxyUrl, List<FieldError> errors) {
    if (proxyUrl == null || proxyUrl.isBlank()) {
      return null;
    }
    if (proxyUrl.strip().length() > MAX_PROXY_URL_LENGTH) {
      errors.add(
          new FieldError(
              "proxyUrl",
              TOO_LONG,
              "Die Proxy-Adresse darf höchstens " + MAX_PROXY_URL_LENGTH + " Zeichen lang sein."));
      return null;
    }
    Optional<ProxyAddress> parsed = ProxyAddress.parse(proxyUrl);
    if (parsed.isEmpty()) {
      errors.add(
          new FieldError(
              "proxyUrl",
              INVALID,
              "Die Proxy-Adresse muss die Form http://host:port oder host:port haben, ohne"
                  + " Benutzer, Pfad oder anderes Schema."));
      return null;
    }
    return parsed.get();
  }

  private static void validateExclusions(
      List<String> raw, List<String> entries, List<FieldError> errors) {
    if (entries.size() > MAX_NO_PROXY_ENTRIES) {
      errors.add(
          new FieldError(
              "noProxy",
              TOO_MANY,
              "Die Ausnahmeliste darf höchstens " + MAX_NO_PROXY_ENTRIES + " Einträge haben."));
      return;
    }
    List<String> tooLong =
        entries.stream().filter(entry -> entry.length() > MAX_NO_PROXY_ENTRY_LENGTH).toList();
    if (!tooLong.isEmpty()) {
      errors.add(
          new FieldError(
              "noProxy",
              TOO_LONG,
              "Ein Eintrag der Ausnahmeliste darf höchstens "
                  + MAX_NO_PROXY_ENTRY_LENGTH
                  + " Zeichen lang sein."));
      return;
    }
    List<String> invalid = ProxyExclusions.invalidEntries(raw);
    if (!invalid.isEmpty()) {
      errors.add(
          new FieldError(
              "noProxy",
              INVALID,
              "Kein gültiger Eintrag der Ausnahmeliste: "
                  + String.join(", ", invalid)
                  + ". Erlaubt sind Hostnamen, IP-Adressen und Netzbereiche (CIDR), ohne Port"
                  + " und ohne „*“."));
    }
  }

  /** The audit trail holds no null: an empty {@code proxyUrl} means every connection direct. */
  private static Map<String, Object> payload(OutboundProxy.Settings settings) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("proxyUrl", settings.proxy() == null ? "" : settings.proxy().toUrl());
    payload.put("noProxy", settings.noProxy());
    return payload;
  }
}
