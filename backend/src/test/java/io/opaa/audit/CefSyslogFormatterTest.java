package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.asset.Asset;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The syslog/CEF form of the SIEM export (ADR-0055, Entscheidung 4). */
class CefSyslogFormatterTest {

  private final CefSyslogFormatter formatter = new CefSyslogFormatter("opaa-backend-0", "0.1.0");
  private final UUID eventId = UUID.randomUUID();
  private final UUID organizationId = UUID.randomUUID();
  private final Instant recordedAt = Instant.parse("2026-10-24T10:15:30.123Z");

  @Test
  void anEntryBecomesOneSyslogMessageWithACefBody() {
    String message =
        formatter.format(
            new AuditExportEntry(
                eventId,
                recordedAt,
                organizationId,
                "LIBRARY_CREATED",
                "KNOWLEDGE_LIBRARY",
                "lib-1",
                "Dienstanweisungen",
                "SUCCESS"));

    assertThat(message)
        .startsWith(
            "<85>1 2026-10-24T10:15:30.123Z opaa-backend-0 opaa - AUDIT - "
                + "CEF:0|OPAA|OPAA|0.1.0|LIBRARY_CREATED|LIBRARY_CREATED|3|")
        .contains("rt=" + recordedAt.toEpochMilli())
        .contains("externalId=" + eventId)
        .contains("outcome=SUCCESS")
        .contains("cs1=" + organizationId)
        .contains("cs2=KNOWLEDGE_LIBRARY")
        .contains("cs3=lib-1")
        .contains("cs4=Dienstanweisungen")
        .doesNotContain("\n");
  }

  @Test
  void anObjectWithoutIdAndLabelLeavesBothOut() {
    String message =
        formatter.format(
            new AuditExportEntry(
                eventId,
                recordedAt,
                organizationId,
                "LOCAL_PASSWORD_CHANGED",
                "USER_ACCOUNT",
                null,
                null,
                "DENIED"));

    assertThat(message).contains("|6|").doesNotContain("cs3").doesNotContain("cs4");
    assertThat(message).startsWith("<84>");
  }

  @Test
  void extensionValuesAndHeaderFieldsAreEscaped() {
    assertThat(CefSyslogFormatter.extensionValue("a=b\\c\nd")).isEqualTo("a\\=b\\\\c\\nd");
    assertThat(CefSyslogFormatter.header("a|b\\c\nd")).isEqualTo("a\\|b\\\\c d");
  }

  @Test
  void aGapNamesItsPeriod() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = Instant.parse("2026-02-01T00:00:00Z");

    String message = formatter.formatGap(from, to, recordedAt, "retention");

    assertThat(message)
        .contains("|" + CefSyslogFormatter.GAP_EVENT + "|")
        .contains("reason=retention")
        .contains("start=" + from.toEpochMilli())
        .contains("end=" + to.toEpochMilli());
  }

  @Test
  void controlCharactersInAFreelyChosenNameBecomeSpaces() {
    String name =
        "a"
            + Character.toString(0x00)
            + "b"
            + Character.toString(0x1B)
            + "c"
            + Character.toString(0x85)
            + "d"
            + Character.toString(0x2028)
            + "e"
            + Character.toString(0x09)
            + "f";
    assertThat(CefSyslogFormatter.extensionValue(name)).isEqualTo("a b c d e f");
    assertThat(CefSyslogFormatter.header("x" + Character.toString(0x07) + "y")).isEqualTo("x y");
  }

  @Test
  void theTimestampHasAtMostSixFractionDigits() {
    String message =
        formatter.formatGap(
            recordedAt, recordedAt, Instant.parse("2026-10-24T10:15:30.123456789Z"), "disabled");

    assertThat(message).contains(" 2026-10-24T10:15:30.123456Z ").contains("reason=disabled");
  }

  @Test
  void thePrivateAssetLabelIsTheNeutralOne() {
    assertThat(Asset.PRIVATE_AUDIT_NAME).isEqualTo(AuditNeutralLabels.PRIVATE_ASSET);
  }
}
