package io.opaa.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-encrypts every value of the {@link EncryptedColumns} that is not yet in the keyed format under
 * the current key (ADR-0064): values under a decrypt-only key after a key rotation, and {@code
 * enc:v1:} values without key id and binding. Legacy cleartext and blank values stay as they are.
 *
 * <p>Runs every ten minutes on one instance ({@link CiphertextRewrapSchedule}), and only once every
 * live instance runs this version and the keyed format is not held back ({@link CipherFormatGate}):
 * an older version would not read what it writes. Each page of rows is claimed with {@code FOR
 * UPDATE SKIP LOCKED} and written back only where the column still holds the value read, so a
 * concurrent write always wins. A run handles at most {@link #RUN_BUDGET} values and leaves the
 * rest to the next one; a value it cannot decrypt is passed over without counting against that
 * budget, counted and logged with table, column and row, never with any part of the value.
 */
@Component
public class CiphertextRewrap {

  static final int PAGE_SIZE = 200;
  static final int RUN_BUDGET = 5_000;

  private static final Logger log = LoggerFactory.getLogger(CiphertextRewrap.class);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final List<EncryptedColumns> sources;
  private final CredentialsEncryptor credentials;
  private final SettingsEncryptor settings;
  private final CipherFormatGate formatGate;

  CiphertextRewrap(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      List<EncryptedColumns> sources,
      CredentialsEncryptor credentials,
      SettingsEncryptor settings,
      CipherFormatGate formatGate) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.sources = List.copyOf(sources);
    this.credentials = credentials;
    this.settings = settings;
    this.formatGate = formatGate;
  }

  /** Unreadable values already named in the log, each warned about once per process. */
  private final Set<CipherBinding> reported = ConcurrentHashMap.newKeySet();

  /** {@code remaining} of the last run of this instance; {@code -1} before the first. */
  private volatile int lastRemaining = -1;

  private volatile int lastUnreadable;

  /**
   * What one run did, summed over all columns: values moved to the current key, values no key
   * decrypts, and readable values still under an old key or format - the unreadable ones are not
   * counted there, so {@code remaining == 0} means the rotation is done.
   */
  public record Outcome(int rewrapped, int unreadable, int remaining, boolean waiting) {}

  /** Runs one pass and logs its result; a failure is logged and left to the next run. */
  void runAndLog() {
    try {
      Outcome outcome = rewrap();
      if (outcome.waiting()) {
        return;
      }
      boolean done = outcome.remaining() == 0;
      if (done && (outcome.rewrapped() > 0 || lastRemaining != 0 && outcome.unreadable() > 0)) {
        log.info(
            "Re-encrypted {} stored secrets; every readable secret is now under the current keys,"
                + " {} cannot be decrypted",
            outcome.rewrapped(),
            outcome.unreadable());
      } else if (outcome.rewrapped() > 0) {
        log.info(
            "Re-encrypted {} stored secrets; {} readable ones still stand under an old key or"
                + " format",
            outcome.rewrapped(),
            outcome.remaining());
      }
      if (outcome.unreadable() > 0 && outcome.unreadable() != lastUnreadable) {
        log.warn(
            "{} stored secrets cannot be decrypted with the current or previous keys and stay"
                + " under their old key; enter them again or restore their key",
            outcome.unreadable());
      }
      lastRemaining = outcome.remaining();
      lastUnreadable = outcome.unreadable();
    } catch (RuntimeException e) {
      log.warn("Stored secrets could not be re-encrypted; the next run tries again", e);
    }
  }

  /** Runs one pass now and reports it; see the class Javadoc. */
  public Outcome rewrap() {
    if (!formatGate.writesKeyedFormat()) {
      log.debug("Re-encryption waits until every live instance runs this version");
      return new Outcome(0, 0, 0, true);
    }
    int budget = RUN_BUDGET;
    int rewrapped = 0;
    int unreadable = 0;
    int remaining = 0;
    for (EncryptedColumn column : columns()) {
      if (!hasKey(column.purpose())) {
        continue;
      }
      String current = EncryptionKeyring.V2_PREFIX + currentKeyId(column.purpose()) + ":";
      int columnUnreadable = 0;
      if (budget > 0) {
        Pass pass = rewrapColumn(column, current, budget);
        budget -= pass.rewrapped();
        rewrapped += pass.rewrapped();
        columnUnreadable = pass.unreadable();
        unreadable += columnUnreadable;
      }
      remaining += Math.max(0, countStale(column, current) - columnUnreadable);
    }
    return new Outcome(rewrapped, unreadable, remaining, false);
  }

  private List<EncryptedColumn> columns() {
    List<EncryptedColumn> columns = new ArrayList<>();
    for (EncryptedColumns source : sources) {
      columns.addAll(source.encryptedColumns());
    }
    return columns;
  }

  private record Pass(int rewrapped, int unreadable) {}

  /**
   * Walks the stale values of {@code column} by id until {@code budget} values are rewritten or the
   * column is done. Only rewritten values count against the budget: an unreadable value is passed
   * by the cursor and never holds back the readable ones behind it.
   */
  private Pass rewrapColumn(EncryptedColumn column, String current, int budget) {
    int rewrapped = 0;
    int unreadable = 0;
    Object after = null;
    while (rewrapped < budget) {
      int limit = Math.min(PAGE_SIZE, budget - rewrapped);
      Object cursor = after;
      Page page = transactions.execute(status -> rewrapPage(column, current, cursor, limit));
      if (page == null || page.size() == 0) {
        break;
      }
      rewrapped += page.rewrapped();
      unreadable += page.unreadable();
      after = page.lastId();
      if (page.size() < limit) {
        break;
      }
    }
    return new Pass(rewrapped, unreadable);
  }

  private record Page(int size, int rewrapped, int unreadable, Object lastId) {}

  private Page rewrapPage(EncryptedColumn column, String current, Object after, int limit) {
    String select =
        "SELECT "
            + column.idColumn()
            + " AS row_id, "
            + column.column()
            + " AS stored FROM "
            + column.table()
            + " WHERE starts_with("
            + column.column()
            + ", 'enc:') AND NOT starts_with("
            + column.column()
            + ", ?)"
            + (after == null ? "" : " AND " + column.idColumn() + " > ?")
            + " ORDER BY "
            + column.idColumn()
            + " LIMIT ? FOR UPDATE SKIP LOCKED";
    List<Map<String, Object>> rows =
        after == null
            ? jdbc.queryForList(select, current, limit)
            : jdbc.queryForList(select, current, after, limit);
    String update =
        "UPDATE "
            + column.table()
            + " SET "
            + column.column()
            + " = ? WHERE "
            + column.idColumn()
            + " = ? AND "
            + column.column()
            + " = ?";
    int rewrapped = 0;
    int unreadable = 0;
    Object lastId = after;
    for (Map<String, Object> row : rows) {
      Object id = row.get("row_id");
      String stored = (String) row.get("stored");
      lastId = id;
      String next;
      try {
        next = rewrapValue(column, stored, column.row(id));
      } catch (RuntimeException e) {
        unreadable++;
        if (reported.add(column.row(id))) {
          log.warn("Stored secret {} cannot be re-encrypted: {}", column.row(id), e.getMessage());
        }
        continue;
      }
      if (!next.equals(stored)) {
        rewrapped += jdbc.update(update, next, id, stored);
      }
    }
    return new Page(rows.size(), rewrapped, unreadable, lastId);
  }

  private int countStale(EncryptedColumn column, String current) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM "
                + column.table()
                + " WHERE starts_with("
                + column.column()
                + ", 'enc:') AND NOT starts_with("
                + column.column()
                + ", ?)",
            Integer.class,
            current);
    return count == null ? 0 : count;
  }

  private String rewrapValue(EncryptedColumn column, String stored, CipherBinding binding) {
    return switch (column.purpose()) {
      case CREDENTIALS -> credentials.rewrap(stored, binding);
      case SETTINGS -> settings.rewrap(stored, binding);
    };
  }

  private boolean hasKey(EncryptionPurpose purpose) {
    return switch (purpose) {
      case CREDENTIALS -> credentials.hasKey();
      case SETTINGS -> settings.hasKey();
    };
  }

  private String currentKeyId(EncryptionPurpose purpose) {
    return switch (purpose) {
      case CREDENTIALS -> credentials.currentKeyId();
      case SETTINGS -> settings.currentKeyId();
    };
  }
}
