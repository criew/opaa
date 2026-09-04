package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.indexing.metadata.CoreMetadata;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The core fields become self-describing list entries in schema order (#1066): an absent field is
 * absent, never a null-padded entry, and a date is displayed at its own precision.
 */
class ChatSourceMetadataEntryTest {

  @Test
  void mapsEveryPresentCoreFieldInSchemaOrder() {
    CoreMetadata core =
        new CoreMetadata(
            "Dienstanweisung IT-Nutzung",
            MetadataOrigin.DETERMINISTIC,
            "DIENSTANWEISUNG",
            "Dienstanweisung",
            MetadataOrigin.MANUAL,
            LocalDate.of(2026, 3, 12),
            DatePrecision.DAY,
            MetadataOrigin.DERIVED);

    List<ChatSourceMetadataEntry> entries = ChatSourceMetadataEntry.fromCore(core);

    assertThat(entries)
        .containsExactly(
            new ChatSourceMetadataEntry(
                "title",
                "Titel",
                "Dienstanweisung IT-Nutzung",
                "Dienstanweisung IT-Nutzung",
                MetadataOrigin.DETERMINISTIC,
                null),
            new ChatSourceMetadataEntry(
                "document_type",
                "Dokumentart",
                "DIENSTANWEISUNG",
                "Dienstanweisung",
                MetadataOrigin.MANUAL,
                null),
            new ChatSourceMetadataEntry(
                "document_date",
                "Datum/Stand",
                "2026-03-12",
                "12.03.2026",
                MetadataOrigin.DERIVED,
                DatePrecision.DAY));
  }

  @Test
  void skipsAbsentFieldsAndYieldsNothingForAnEmptyOrNullRecord() {
    CoreMetadata onlyDate =
        new CoreMetadata(
            null,
            null,
            null,
            null,
            null,
            LocalDate.of(2024, 1, 1),
            DatePrecision.YEAR,
            MetadataOrigin.DETERMINISTIC);

    assertThat(ChatSourceMetadataEntry.fromCore(onlyDate))
        .extracting(ChatSourceMetadataEntry::fieldKey, ChatSourceMetadataEntry::displayValue)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("document_date", "2024"));
    assertThat(ChatSourceMetadataEntry.fromCore(CoreMetadata.EMPTY)).isEmpty();
    assertThat(ChatSourceMetadataEntry.fromCore(null)).isEmpty();
  }

  @Test
  void displaysADateAtItsOwnPrecisionNeverAsAPaddedDay() {
    assertThat(ChatSourceMetadataEntry.displayDate(LocalDate.of(2026, 3, 12), DatePrecision.DAY))
        .isEqualTo("12.03.2026");
    assertThat(ChatSourceMetadataEntry.displayDate(LocalDate.of(2026, 3, 1), DatePrecision.MONTH))
        .isEqualTo("03/2026");
    assertThat(ChatSourceMetadataEntry.displayDate(LocalDate.of(2024, 1, 1), DatePrecision.YEAR))
        .isEqualTo("2024");
  }
}
