package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.indexing.metadata.CitationFieldValue;
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

  /**
   * #1242: a mail's format fields follow the core fields as further entries, and the Betreff -
   * which is also the Titel of the document - is not repeated in the same Belegzeile.
   */
  @Test
  void appendsFormatFieldsAndDropsAValueTheTitleAlreadyShows() {
    CoreMetadata core =
        new CoreMetadata(
            "Bebauungsplan Nord",
            MetadataOrigin.DETERMINISTIC,
            null,
            null,
            null,
            LocalDate.of(2026, 3, 12),
            DatePrecision.DAY,
            MetadataOrigin.DETERMINISTIC);
    List<CitationFieldValue> fields =
        List.of(
            new CitationFieldValue(
                "fmt:mail_sender",
                "Absender",
                "max@stadt.de",
                "max@stadt.de",
                MetadataOrigin.DETERMINISTIC,
                null),
            new CitationFieldValue(
                "fmt:mail_subject",
                "Betreff",
                "Bebauungsplan Nord",
                "Bebauungsplan Nord",
                MetadataOrigin.DETERMINISTIC,
                null));

    List<ChatSourceMetadataEntry> entries = ChatSourceMetadataEntry.from(core, fields);

    assertThat(entries)
        .extracting(ChatSourceMetadataEntry::label, ChatSourceMetadataEntry::displayValue)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("Titel", "Bebauungsplan Nord"),
            org.assertj.core.groups.Tuple.tuple("Datum/Stand", "12.03.2026"),
            org.assertj.core.groups.Tuple.tuple("Absender", "max@stadt.de"));
  }

  /**
   * regression guard for #1242: an entry marked detail-only travels to the client like every other
   * one - only the client's one-line Beleg leaves it out. The flag must survive the mapping.
   */
  @Test
  void carriesTheDetailOnlyFlagOfAnEntry() {
    List<ChatSourceMetadataEntry> entries =
        ChatSourceMetadataEntry.from(
            CoreMetadata.EMPTY,
            List.of(
                new CitationFieldValue(
                    "fmt:mail_recipients",
                    "An",
                    "a@x.de; b@y.de",
                    "a@x.de; b@y.de",
                    MetadataOrigin.DETERMINISTIC,
                    null,
                    true)));

    assertThat(entries).singleElement().satisfies(entry -> assertThat(entry.detailOnly()).isTrue());
  }

  /**
   * regression guard for #1242: the Belegzeile carries at most two non-core entries, and a value
   * dropped as a duplicate of the Titel must not cost the place a library field could have had -
   * deduplication runs first, the cap second. A detail-only entry is not on the line and does not
   * count either.
   */
  @Test
  void deduplicatesBeforeCappingTheLineAndDoesNotCountDetailOnlyEntries() {
    CoreMetadata core =
        new CoreMetadata(
            "Bebauungsplan Nord", MetadataOrigin.DETERMINISTIC, null, null, null, null, null, null);
    List<CitationFieldValue> fields =
        List.of(
            citation("fmt:mail_subject", "Betreff", "Bebauungsplan Nord", false),
            citation("fmt:mail_recipients", "An", "a@x.de; b@y.de", true),
            citation("fmt:mail_sender", "Absender", "max@stadt.de", false),
            citation("lib:fassung", "Fassung", "Fassung 2026", false),
            citation("lib:projekt", "Projekt", "Nordspange", false));

    List<ChatSourceMetadataEntry> entries = ChatSourceMetadataEntry.from(core, fields);

    assertThat(entries)
        .extracting(ChatSourceMetadataEntry::label)
        .containsExactly("Titel", "An", "Absender", "Fassung");
  }

  private static CitationFieldValue citation(
      String fieldKey, String label, String value, boolean detailOnly) {
    return new CitationFieldValue(
        fieldKey, label, value, value, MetadataOrigin.DETERMINISTIC, null, detailOnly);
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
