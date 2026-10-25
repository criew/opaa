package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.audit.AuditChainHasher.ChainedEntry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The canonical form and the chaining of {@link AuditChainHasher} (ADR-0059, Entscheidung 4): every
 * column counts, NULL and the empty string differ, the order of the entries matters and a chain of
 * the same entries over the same start yields the same head.
 */
class AuditChainHasherTest {

  private static final UUID EVENT = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant RECORDED = Instant.parse("2026-09-14T10:15:30.123456Z");
  private static final AuditChainHasher HASHER = AuditChainHasher.CURRENT;

  @Test
  void theCanonicalFormMarksNullAndLengthPrefixesEveryValue() {
    byte[] canonical = AuditChainHasher.canonical(entry("SUCCESS", null));

    // event_id: present, length 16, 16 bytes; recorded_at: present, length 8, 8 bytes
    assertThat(canonical[0]).isEqualTo((byte) 1);
    assertThat(canonical[4]).isEqualTo((byte) 16);
    assertThat(canonical[21]).isEqualTo((byte) 1);
    assertThat(canonical[25]).isEqualTo((byte) 8);
    long micros = RECORDED.getEpochSecond() * 1_000_000L + RECORDED.getNano() / 1_000;
    assertThat(ByteBuffer.wrap(canonical, 26, 8).getLong()).isEqualTo(micros);
    // a NULL column is one byte 0x00
    int nulls = 0;
    for (byte b : canonical) {
      if (b == 0) {
        nulls++;
      }
    }
    assertThat(nulls).isGreaterThan(0);
  }

  @Test
  void nullAndTheEmptyStringAreDifferentEntries() {
    assertThat(AuditChainHasher.canonical(entry("SUCCESS", null)))
        .isNotEqualTo(AuditChainHasher.canonical(entry("SUCCESS", "")));
  }

  @Test
  void everyColumnEntersTheHash() {
    byte[] start = HASHER.start(YearMonth.of(2026, 9));

    assertThat(HASHER.next(start, entry("SUCCESS", "x")))
        .isNotEqualTo(HASHER.next(start, entry("FAILURE", "x")))
        .isNotEqualTo(HASHER.next(start, entry("SUCCESS", "y")));
  }

  @Test
  void theOrderOfTheEntriesMatters() {
    byte[] start = HASHER.start(YearMonth.of(2026, 9));
    ChainedEntry a = entry("SUCCESS", "a");
    ChainedEntry b = entry("SUCCESS", "b");

    byte[] ab = HASHER.next(HASHER.next(start, a), b);
    byte[] ba = HASHER.next(HASHER.next(start, b), a);

    assertThat(ab).isNotEqualTo(ba);
    assertThat(HASHER.next(HASHER.next(start, a), b)).isEqualTo(ab);
  }

  @Test
  void theStartDependsOnTheMonthAndTheAlgorithm() {
    assertThat(HASHER.start(YearMonth.of(2026, 9)))
        .hasSize(32)
        .isNotEqualTo(HASHER.start(YearMonth.of(2026, 10)));
    assertThat(AuditChainHasher.hex(HASHER.start(YearMonth.of(2026, 9))))
        .hasSize(64)
        .matches("[0-9a-f]{64}");
    assertThat(AuditChainHasher.ALGORITHM.getBytes(StandardCharsets.US_ASCII))
        .hasSizeLessThanOrEqualTo(40);
  }

  @Test
  void theSchemaVersionEntersTheHashAsTwoBytes() {
    byte[] versioned = AuditChainHasher.canonical(entry("SUCCESS", null));
    byte[] unversioned = AuditChainHasher.canonical(entry("SUCCESS", null, null));

    assertThat(versioned).isNotEqualTo(unversioned);
    // the last field: present, length 2, big-endian value
    int n = versioned.length;
    assertThat(versioned[n - 7]).isEqualTo((byte) 1);
    assertThat(versioned[n - 3]).isEqualTo((byte) 2);
    assertThat(ByteBuffer.wrap(versioned, n - 2, 2).getShort())
        .isEqualTo(AuditEventCatalog.SCHEMA_VERSION);
    assertThat(unversioned[unversioned.length - 1]).isEqualTo((byte) 0);
  }

  @Test
  void theSelectListNamesEveryColumnOfTheLogOnce() {
    assertThat(AuditChainHasher.COLUMNS).hasSize(17).doesNotHaveDuplicates();
    assertThat(AuditChainHasher.SELECT_COLUMNS)
        .startsWith("event_id, recorded_at")
        .endsWith(", schema_version");
  }

  @Test
  void aProcedureIsFoundByItsNameAndAnUnknownOneIsNotAnError() {
    assertThat(AuditChainHasher.forAlgorithm(AuditChainHasher.ALGORITHM))
        .contains(HASHER)
        .map(AuditChainHasher::name)
        .contains("OPAA-AUDIT-CHAIN-1");
    assertThat(AuditChainHasher.forAlgorithm("OPAA-AUDIT-CHAIN-9")).isEmpty();
    assertThat(AuditChainHasher.forAlgorithm(null)).isEmpty();
  }

  private static ChainedEntry entry(String outcome, String reason) {
    return entry(outcome, reason, AuditEventCatalog.SCHEMA_VERSION);
  }

  private static ChainedEntry entry(String outcome, String reason, Short schemaVersion) {
    return new ChainedEntry(
        EVENT,
        RECORDED,
        ORGANIZATION,
        "USER",
        "pseud-1",
        "LIBRARY_CREATED",
        "KNOWLEDGE_LIBRARY",
        "lib-1",
        "Bibliothek",
        null,
        null,
        null,
        "{\"name\":\"x\"}",
        outcome,
        reason,
        null,
        schemaVersion);
  }
}
