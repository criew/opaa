package io.opaa.common;

import io.opaa.compliance.SelfDisclosureLabels;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSink.RecordWriter;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Copies one section of a self-disclosure category from the database into the sink, as the port
 * {@link io.opaa.compliance.SelfDisclosureExporter} asks: page by page in keyset order, each page
 * in a short read-only transaction, written after it ended. A page row carries the keyset under
 * {@link #KEY_TIME} and {@link #KEY_ID}; both are removed before the row is written. The section is
 * opened at its first row, so a section without rows does not appear.
 */
public final class SelfDisclosureSectionReader {

  /** Rows per page. */
  public static final int PAGE_SIZE = 500;

  /** The time of the keyset, a {@link Instant}; removed before the row is written. */
  public static final String KEY_TIME = "_keysetTime";

  /** The tie-breaking id of the keyset, a {@link UUID}; removed before the row is written. */
  public static final String KEY_ID = "_keysetId";

  private static final Instant START_TIME = Instant.parse("1900-01-01T00:00:00Z");
  private static final UUID START_ID = new UUID(0, 0);

  private final TransactionTemplate readOnly;

  public SelfDisclosureSectionReader(PlatformTransactionManager transactionManager) {
    this.readOnly = new TransactionTemplate(transactionManager);
    this.readOnly.setReadOnly(true);
  }

  /**
   * One page of rows strictly after {@code (afterTime, afterId)}, ordered by time and id, at most
   * {@code limit} of them.
   */
  @FunctionalInterface
  public interface PageQuery {
    List<Map<String, Object>> page(Timestamp afterTime, UUID afterId, int limit);
  }

  /** Writes every row {@code query} yields into the section {@code key} of {@code sink}. */
  public void copy(SelfDisclosureSink sink, String key, PageQuery query) throws IOException {
    RecordWriter writer = null;
    Instant afterTime = START_TIME;
    UUID afterId = START_ID;
    while (true) {
      Timestamp cursorTime = Timestamp.from(afterTime);
      UUID cursorId = afterId;
      List<Map<String, Object>> page =
          readOnly.execute(status -> query.page(cursorTime, cursorId, PAGE_SIZE));
      if (page == null || page.isEmpty()) {
        return;
      }
      if (writer == null) {
        writer = sink.section(key);
      }
      for (Map<String, Object> row : page) {
        afterTime = (Instant) row.remove(KEY_TIME);
        afterId = (UUID) row.remove(KEY_ID);
        writer.write(row);
      }
      if (page.size() < PAGE_SIZE) {
        return;
      }
    }
  }

  /**
   * A new record carrying only the keyset of the current row of {@code rs}, read from {@code
   * timeColumn} (not null) and {@code idColumn}.
   */
  public static Map<String, Object> keyed(ResultSet rs, String timeColumn, String idColumn)
      throws SQLException {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put(KEY_TIME, rs.getTimestamp(timeColumn).toInstant());
    record.put(KEY_ID, rs.getObject(idColumn, UUID.class));
    return record;
  }

  /** The instant of a nullable timestamp column. */
  public static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  /**
   * The fixed word for the account {@code account} as seen by {@code subject}: "Sie", "andere
   * Person", or {@code absent} when there is no account.
   */
  public static String who(UUID account, UUID subject, String absent) {
    if (account == null) {
      return absent;
    }
    return account.equals(subject) ? SelfDisclosureLabels.YOU : SelfDisclosureLabels.OTHER_PERSON;
  }
}
