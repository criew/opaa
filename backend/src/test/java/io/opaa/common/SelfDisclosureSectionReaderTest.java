package io.opaa.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.SelfDisclosureSink;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The page loop of {@link SelfDisclosureSectionReader} with a small page size over an in-memory
 * keyset query: every row once and in keyset order across page boundaries, also when many rows
 * share one time, each page in its own read-only transaction and every write outside of it.
 */
class SelfDisclosureSectionReaderTest {

  private static final Instant SAME = Instant.parse("2026-10-23T12:00:00Z");

  private final RecordingTransactions transactions = new RecordingTransactions();
  private final RecordingSink sink = new RecordingSink(transactions);
  private final List<Integer> pageSizes = new ArrayList<>();

  @Test
  void readsEveryRowOnceInKeysetOrderAcrossPagesWithEqualTimes() throws Exception {
    List<Map<String, Object>> rows = rows(7, 5);

    new SelfDisclosureSectionReader(transactions, 2).copy(sink, "main", query(rows));

    assertThat(rows).hasSize(7);
    assertThat(sink.records.stream().map(r -> (Integer) r.get("n")).toList())
        .containsExactly(0, 1, 2, 3, 4, 5, 6);
    assertThat(sink.records).allSatisfy(r -> assertThat(r).containsOnlyKeys("n"));
    assertThat(sink.sections).containsExactly("main");
    assertThat(pageSizes).containsExactly(2, 2, 2, 1);
    assertThat(transactions.started).isEqualTo(4);
    assertThat(transactions.allReadOnly).isTrue();
  }

  @Test
  void anExactMultipleOfThePageSizeEndsWithOneEmptyPage() throws Exception {
    List<Map<String, Object>> rows = rows(6, 6);

    new SelfDisclosureSectionReader(transactions, 2).copy(sink, "main", query(rows));

    assertThat(sink.records).hasSize(6);
    assertThat(pageSizes).containsExactly(2, 2, 2, 0);
  }

  @Test
  void noRowOpensNoSection() throws Exception {
    new SelfDisclosureSectionReader(transactions, 2).copy(sink, "main", query(List.of()));

    assertThat(sink.sections).isEmpty();
  }

  /** {@code count} rows, the first {@code sharingTime} of them at one time, in keyset order. */
  private static List<Map<String, Object>> rows(int count, int sharingTime) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("time", i < sharingTime ? SAME : SAME.plusSeconds(i));
      row.put("id", UUID.randomUUID());
      row.put("n", i);
      rows.add(row);
    }
    rows.sort(KEYSET);
    for (int i = 0; i < rows.size(); i++) {
      rows.get(i).put("n", i);
    }
    return rows;
  }

  /** Time, then id in the byte order Postgres compares uuids in. */
  private static final Comparator<Map<String, Object>> KEYSET =
      Comparator.<Map<String, Object>, Instant>comparing(r -> (Instant) r.get("time"))
          .thenComparing(r -> r.get("id").toString());

  private SelfDisclosureSectionReader.PageQuery query(List<Map<String, Object>> rows) {
    return (afterTime, afterId, limit) -> {
      assertThat(transactions.inTransaction).isTrue();
      Map<String, Object> cursor = Map.of("time", afterTime.toInstant(), "id", afterId);
      List<Map<String, Object>> page = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        if (KEYSET.compare(row, cursor) > 0 && page.size() < limit) {
          Map<String, Object> record = new LinkedHashMap<>();
          record.put(SelfDisclosureSectionReader.KEY_TIME, row.get("time"));
          record.put(SelfDisclosureSectionReader.KEY_ID, row.get("id"));
          record.put("n", row.get("n"));
          page.add(record);
        }
      }
      pageSizes.add(page.size());
      return page;
    };
  }

  private static final class RecordingTransactions implements PlatformTransactionManager {

    int started;
    boolean inTransaction;
    boolean allReadOnly = true;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      started++;
      inTransaction = true;
      allReadOnly &= definition != null && definition.isReadOnly();
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      inTransaction = false;
    }

    @Override
    public void rollback(TransactionStatus status) {
      inTransaction = false;
    }
  }

  private static final class RecordingSink implements SelfDisclosureSink {

    final List<String> sections = new ArrayList<>();
    final List<Map<String, ?>> records = new ArrayList<>();
    private final RecordingTransactions transactions;

    RecordingSink(RecordingTransactions transactions) {
      this.transactions = transactions;
    }

    @Override
    public RecordWriter section(String key) {
      sections.add(key);
      return record -> {
        assertThat(transactions.inTransaction).as("written inside the read").isFalse();
        records.add(record);
      };
    }
  }
}
