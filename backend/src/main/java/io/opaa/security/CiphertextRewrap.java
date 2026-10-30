package io.opaa.security;

import java.util.ArrayList;
import java.util.EnumMap;
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
 * enc:v1:} values without key id and binding. Blank values stay as they are.
 *
 * <p>Runs every ten minutes on one instance ({@link CiphertextRewrapSchedule}). While {@link
 * CipherFormatGate} holds the keyed format back - an older instance still runs, or the operator
 * keeps the way back open - it keeps each value's format and only moves it to the current key, so a
 * key rotation completes either way and an older version given the current key reads every {@code
 * enc:v1:} value. Each page of rows is claimed with {@code FOR UPDATE SKIP LOCKED} and written back
 * only where the column still holds the value read, so a concurrent write always wins. A run
 * handles at most {@link #RUN_BUDGET} values and leaves the rest to the next one; a value it cannot
 * decrypt is passed over without counting against that budget, counted and logged with table,
 * column and row, never with any part of the value.
 *
 * <p>A run with the keyed format open also encrypts legacy cleartext of the credential columns
 * whose readers use it, never of one that refuses it ({@link EncryptedColumn#cleartextRefused()}):
 * that would make it usable. Before its pass it counts, per purpose, the values still in an unbound
 * form - {@code enc:v1:} or such cleartext, readable or not - and reports the count to {@link
 * LegacyCipherRetirement}, which retires the purpose after enough runs found none. Once retired, a
 * purpose's unbound values are refused by its encryptor, and the run neither selects nor moves
 * them.
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
  private final LegacyCipherRetirement retirement;

  CiphertextRewrap(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      List<EncryptedColumns> sources,
      CredentialsEncryptor credentials,
      SettingsEncryptor settings,
      CipherFormatGate formatGate,
      LegacyCipherRetirement retirement) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.sources = List.copyOf(sources);
    this.credentials = credentials;
    this.settings = settings;
    this.formatGate = formatGate;
    this.retirement = retirement;
  }

  /**
   * Most unreadable values named one by one per process; the count covers the rest. This and the
   * log state below are per instance (ADR-0053).
   */
  static final int REPORT_LIMIT = 1_000;

  /** Unreadable values already named in the log, each warned about once per process. */
  private final Set<CipherBinding> reported = ConcurrentHashMap.newKeySet();

  /** Whether this process has warned that a rotation runs while the keyed format is held back. */
  private volatile boolean heldBackWarned;

  /** {@code remaining} of the last run of this instance; {@code -1} before the first. */
  private volatile int lastRemaining = -1;

  private volatile int lastUnreadable;

  /**
   * What one run did, summed over all columns: values moved to the current key, values no key
   * decrypts, and readable values still under an old key or format - the unreadable ones are not
   * counted there, so {@code remaining == 0} means the rotation is done. {@code heldBack}: the run
   * kept the format of each value, see the class Javadoc; {@code remaining} then counts only values
   * not under the current key.
   */
  public record Outcome(int rewrapped, int unreadable, int remaining, boolean heldBack) {}

  /** Runs one pass and logs its result; a failure is logged and left to the next run. */
  void runAndLog() {
    try {
      Outcome outcome = rewrap();
      warnIfRotatingHeldBack(outcome);
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

  private void warnIfRotatingHeldBack(Outcome outcome) {
    boolean rotating = credentials.hasPreviousKeys() || settings.hasPreviousKeys();
    if (!rotating) {
      heldBackWarned = false;
      return;
    }
    if (outcome.heldBack() && !heldBackWarned) {
      heldBackWarned = true;
      log.warn(
          "Previous encryption keys are set while the keyed format is held back ({}=false, or an"
              + " instance of an older version still runs): stored secrets move to the current key"
              + " in the unbound format enc:v1 and stay so until the keyed format is opened",
          CipherFormatProperties.KEYED_VARIABLE);
    }
  }

  /** Runs one pass now and reports it; see the class Javadoc. */
  public Outcome rewrap() {
    boolean keyed = formatGate.writesKeyedFormat();
    Map<EncryptionPurpose, Integer> unbound = keyed ? countUnboundBeforeThePass() : Map.of();
    int budget = RUN_BUDGET;
    int rewrapped = 0;
    int unreadable = 0;
    int remaining = 0;
    for (EncryptedColumn column : columns()) {
      if (!hasKey(column.purpose())) {
        continue;
      }
      String current = EncryptionKeyring.V2_PREFIX + currentKeyId(column.purpose()) + ":";
      List<Selector> selectors = new ArrayList<>();
      boolean refusing = retirement.refusesLegacy(column.purpose());
      String scope = refusing ? EncryptionKeyring.V2_PREFIX : scope(column.purpose(), keyed);
      selectors.add(stale(column, scope, current));
      if (keyed && column.acceptsCleartext() && !refusing) {
        selectors.add(cleartext(column));
      }
      for (Selector selector : selectors) {
        int selectorUnreadable = 0;
        int selectorSettled = 0;
        if (budget > 0) {
          Pass pass = rewrapColumn(column, selector, keyed, budget);
          budget -= pass.rewrapped();
          rewrapped += pass.rewrapped();
          selectorUnreadable = pass.unreadable();
          selectorSettled = pass.settled();
          unreadable += selectorUnreadable;
        }
        remaining += Math.max(0, count(column, selector) - selectorUnreadable - selectorSettled);
      }
    }
    unbound.forEach(retirement::measured);
    return new Outcome(rewrapped, unreadable, remaining, !keyed);
  }

  /**
   * The unbound values of each purpose not yet retired, counted before the pass moves them: a value
   * written by an instance that still held the keyed format back shows up here, so it postpones the
   * retirement although this very pass re-encrypts it. A purpose without a current key holds
   * nothing it could retire.
   */
  private Map<EncryptionPurpose, Integer> countUnboundBeforeThePass() {
    Map<EncryptionPurpose, Integer> unbound = new EnumMap<>(EncryptionPurpose.class);
    for (EncryptedColumn column : columns()) {
      EncryptionPurpose purpose = column.purpose();
      if (!hasKey(purpose) || retirement.refusesLegacy(purpose)) {
        continue;
      }
      int legacy = count(column, unboundFormat(column));
      if (column.acceptsCleartext()) {
        legacy += count(column, cleartext(column));
      }
      unbound.merge(purpose, legacy, Integer::sum);
    }
    return unbound;
  }

  /**
   * The prefix of the values a run looks at. With the keyed format held back and no decrypt-only
   * key, an {@code enc:v1:} value is under the current key or under none, so the run leaves every
   * one of them alone - unread and unlocked - and moves only keyed values under another key.
   */
  private String scope(EncryptionPurpose purpose, boolean keyed) {
    boolean previousKeys =
        switch (purpose) {
          case CREDENTIALS -> credentials.hasPreviousKeys();
          case SETTINGS -> settings.hasPreviousKeys();
        };
    return keyed || previousKeys ? EncryptionKeyring.MARKER : EncryptionKeyring.V2_PREFIX;
  }

  private List<EncryptedColumn> columns() {
    List<EncryptedColumn> columns = new ArrayList<>();
    for (EncryptedColumns source : sources) {
      columns.addAll(source.encryptedColumns());
    }
    return columns;
  }

  /** A condition on the column's value, as SQL with its parameters. */
  private record Selector(String where, List<Object> parameters) {}

  /** Values under {@code scope} that are not under the current key. */
  private static Selector stale(EncryptedColumn column, String scope, String current) {
    return new Selector(
        "starts_with(" + column.column() + ", ?) AND NOT starts_with(" + column.column() + ", ?)",
        List.of(scope, current));
  }

  private static Selector unboundFormat(EncryptedColumn column) {
    return new Selector(
        "starts_with(" + column.column() + ", ?)", List.of(EncryptionKeyring.V1_PREFIX));
  }

  /** Legacy cleartext: a value with a non-blank character and without the marker. */
  private static Selector cleartext(EncryptedColumn column) {
    return new Selector(
        "NOT starts_with(" + column.column() + ", ?) AND " + column.column() + " ~ '\\S'",
        List.of(EncryptionKeyring.MARKER));
  }

  /**
   * {@code settled}: values under the current key in the {@code enc:v1:} format, which the stale
   * selector still matches; only a run that keeps the format leaves such values.
   */
  private record Pass(int rewrapped, int unreadable, int settled) {}

  /**
   * Walks the values of {@code column} that {@code selector} matches by id until {@code budget}
   * values are rewritten or the column is done. Only rewritten values count against the budget: an
   * unreadable value is passed by the cursor and never holds back the readable ones behind it.
   */
  private Pass rewrapColumn(EncryptedColumn column, Selector selector, boolean keyed, int budget) {
    int rewrapped = 0;
    int unreadable = 0;
    int settled = 0;
    Object after = null;
    while (rewrapped < budget) {
      int limit = Math.min(PAGE_SIZE, budget - rewrapped);
      Object cursor = after;
      Page page =
          transactions.execute(status -> rewrapPage(column, selector, keyed, cursor, limit));
      if (page == null || page.size() == 0) {
        break;
      }
      rewrapped += page.rewrapped();
      unreadable += page.unreadable();
      settled += page.settled();
      after = page.lastId();
      if (page.size() < limit) {
        break;
      }
    }
    return new Pass(rewrapped, unreadable, settled);
  }

  private record Page(int size, int rewrapped, int unreadable, int settled, Object lastId) {}

  private Page rewrapPage(
      EncryptedColumn column, Selector selector, boolean keyed, Object after, int limit) {
    String select =
        "SELECT "
            + column.idColumn()
            + " AS row_id, "
            + column.column()
            + " AS stored FROM "
            + column.table()
            + " WHERE "
            + selector.where()
            + (after == null ? "" : " AND " + column.idColumn() + " > ?")
            + " ORDER BY "
            + column.idColumn()
            + " LIMIT ? FOR UPDATE SKIP LOCKED";
    List<Object> arguments = new ArrayList<>(selector.parameters());
    if (after != null) {
      arguments.add(after);
    }
    arguments.add(limit);
    List<Map<String, Object>> rows = jdbc.queryForList(select, arguments.toArray());
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
    int settled = 0;
    Object lastId = after;
    for (Map<String, Object> row : rows) {
      Object id = row.get("row_id");
      String stored = (String) row.get("stored");
      lastId = id;
      String next;
      try {
        next = rewrapValue(column, stored, column.row(id), keyed);
      } catch (RuntimeException e) {
        unreadable++;
        if (reported.size() < REPORT_LIMIT && reported.add(column.row(id))) {
          log.warn("Stored secret {} cannot be re-encrypted: {}", column.row(id), e.getMessage());
        }
        continue;
      }
      if (!next.equals(stored)) {
        rewrapped += jdbc.update(update, next, id, stored);
      }
      if (next.startsWith(EncryptionKeyring.V1_PREFIX)) {
        settled++;
      }
    }
    return new Page(rows.size(), rewrapped, unreadable, settled, lastId);
  }

  private int count(EncryptedColumn column, Selector selector) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + column.table() + " WHERE " + selector.where(),
            Integer.class,
            selector.parameters().toArray());
    return count == null ? 0 : count;
  }

  private String rewrapValue(
      EncryptedColumn column, String stored, CipherBinding binding, boolean keyed) {
    return switch (column.purpose()) {
      case CREDENTIALS -> credentials.rewrap(stored, binding, keyed);
      case SETTINGS -> settings.rewrap(stored, binding, keyed);
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
