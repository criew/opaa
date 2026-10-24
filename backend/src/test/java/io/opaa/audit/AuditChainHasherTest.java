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
 * The canonical form and the chaining of {@link AuditChainHasher} (ADR-0058, Entscheidung 4): every
 * column counts, NULL and the empty string differ, the order of the entries matters and a chain of
 * the same entries over the same start yields the same head.
 */
class AuditChainHasherTest {

  private static final UUID EVENT = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant RECORDED = Instant.parse("2026-09-14T10:15:30.123456Z");

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
    byte[] start = AuditChainHasher.start(YearMonth.of(2026, 9));

    assertThat(AuditChainHasher.next(start, entry("SUCCESS", "x")))
        .isNotEqualTo(AuditChainHasher.next(start, entry("FAILURE", "x")))
        .isNotEqualTo(AuditChainHasher.next(start, entry("SUCCESS", "y")));
  }

  @Test
  void theOrderOfTheEntriesMatters() {
    byte[] start = AuditChainHasher.start(YearMonth.of(2026, 9));
    ChainedEntry a = entry("SUCCESS", "a");
    ChainedEntry b = entry("SUCCESS", "b");

    byte[] ab = AuditChainHasher.next(AuditChainHasher.next(start, a), b);
    byte[] ba = AuditChainHasher.next(AuditChainHasher.next(start, b), a);

    assertThat(ab).isNotEqualTo(ba);
    assertThat(AuditChainHasher.next(AuditChainHasher.next(start, a), b)).isEqualTo(ab);
  }

  @Test
  void theStartDependsOnTheMonthAndTheAlgorithm() {
    assertThat(AuditChainHasher.start(YearMonth.of(2026, 9)))
        .hasSize(32)
        .isNotEqualTo(AuditChainHasher.start(YearMonth.of(2026, 10)));
    assertThat(AuditChainHasher.hex(AuditChainHasher.start(YearMonth.of(2026, 9))))
        .hasSize(64)
        .matches("[0-9a-f]{64}");
    assertThat(AuditChainHasher.ALGORITHM.getBytes(StandardCharsets.US_ASCII))
        .hasSizeLessThanOrEqualTo(40);
  }

  @Test
  void theSelectListNamesEveryColumnOfTheLogOnce() {
    assertThat(AuditChainHasher.COLUMNS).hasSize(16).doesNotHaveDuplicates();
    assertThat(AuditChainHasher.SELECT_COLUMNS).startsWith("event_id, recorded_at");
  }

  private static ChainedEntry entry(String outcome, String reason) {
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
        null);
  }
}
