package io.opaa.instance;

import io.opaa.instance.InstanceAdmission.LiveInstance;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The instance register in {@code backend_instances} (ADR-0053, Entscheidung 3). Every point in
 * time is taken and compared by the database clock inside the statement, so a host clock that runs
 * ahead or behind neither keeps a dead instance alive nor declares a live one dead.
 *
 * <p>Registration is serialised over the installation by a transaction-scoped advisory lock: two
 * instances starting at the same moment each see the other's row, or neither starts with a
 * fingerprint the other has not checked.
 */
public class InstanceRegistry {

  /** Advisory lock namespace of the register; the second key is always 0. */
  static final int REGISTER_LOCK_NAMESPACE = 231;

  /** How long a dead row stays visible before a heartbeat sweeps it. */
  static final Duration DEAD_ROW_RETENTION = Duration.ofDays(7);

  private static final int HMAC_KEY_BYTES = 32;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final TypeReference<TreeMap<String, String>> DIGESTS = new TypeReference<>() {};

  private static final String ALIVE =
      "(stopped_at IS NULL AND heartbeat_at >= clock_timestamp() - make_interval(secs => ?))";

  private final JdbcOperations jdbc;
  private final TransactionOperations transactions;
  private final Duration deadAfter;
  private final SecureRandom random = new SecureRandom();

  InstanceRegistry(
      JdbcOperations jdbc, TransactionOperations transactions, InstanceProperties properties) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.deadAfter = properties.deadAfter();
  }

  /**
   * Admits and registers {@code id} with a fresh heartbeat, or refuses it without leaving a row.
   *
   * @throws InstanceStartRefusedException when its version or fingerprint does not fit a live
   *     instance
   */
  void register(UUID id, String hostName, InstanceVersion version, List<FingerprintValue> values) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.queryForObject(
              "SELECT 1 FROM (SELECT pg_advisory_xact_lock(?, 0)) locked",
              Integer.class,
              REGISTER_LOCK_NAMESPACE);
          sweepDeadRows();
          byte[] hmacKey = hmacKey();
          ConfigurationFingerprint fingerprint = ConfigurationFingerprint.of(values, hmacKey);
          InstanceAdmission.check(id, version, fingerprint, values, liveInstances(hmacKey));
          jdbc.update(
              "INSERT INTO backend_instances (id, host_name, version, oldest_compatible_version,"
                  + " started_at, heartbeat_at, fingerprint, fingerprint_hash) VALUES (?, ?, ?, ?,"
                  + " clock_timestamp(), clock_timestamp(), CAST(? AS jsonb), ?)",
              id,
              hostName,
              version.version(),
              version.oldestCompatible(),
              JSON.writeValueAsString(fingerprint.digests()),
              fingerprint.hash(hmacKey));
        });
  }

  /**
   * Writes the heartbeat of {@code id}, unless it signed off or its heartbeat already expired: an
   * instance declared dead stays dead, the runs it held may already belong to another.
   *
   * @return whether the heartbeat was written
   */
  boolean heartbeat(UUID id) {
    return jdbc.update(
            "UPDATE backend_instances SET heartbeat_at = clock_timestamp() WHERE id = ? AND "
                + ALIVE,
            id,
            seconds(deadAfter))
        == 1;
  }

  /** Signs {@code id} off; from now on it counts as dead. */
  void deregister(UUID id) {
    jdbc.update(
        "UPDATE backend_instances SET stopped_at = clock_timestamp() WHERE id = ? AND stopped_at"
            + " IS NULL",
        id);
  }

  /** Deletes the rows of instances dead for longer than {@link #DEAD_ROW_RETENTION}. */
  void sweepDeadRows() {
    jdbc.update(
        "DELETE FROM backend_instances WHERE coalesce(stopped_at, heartbeat_at) <"
            + " clock_timestamp() - make_interval(secs => ?)",
        seconds(DEAD_ROW_RETENTION));
  }

  /** Every registered instance, live and dead, the latest start first. */
  public List<RegisteredInstance> instances() {
    return jdbc.query(
        "SELECT id, host_name, version, started_at, heartbeat_at, stopped_at, "
            + ALIVE
            + " AS alive FROM backend_instances ORDER BY started_at DESC, id",
        (rs, row) ->
            new RegisteredInstance(
                rs.getObject("id", UUID.class),
                rs.getString("host_name"),
                rs.getString("version"),
                instant(rs, "started_at"),
                instant(rs, "heartbeat_at"),
                instant(rs, "stopped_at"),
                rs.getBoolean("alive")),
        seconds(deadAfter));
  }

  /** Whether {@code id} is registered and alive by the database clock. */
  public boolean isAlive(UUID id) {
    Boolean alive =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM backend_instances WHERE id = ? AND " + ALIVE + ")",
            Boolean.class,
            id,
            seconds(deadAfter));
    return Boolean.TRUE.equals(alive);
  }

  /** The fingerprints of the live instances, by instance. */
  SortedMap<UUID, ConfigurationFingerprint> liveFingerprints() {
    SortedMap<UUID, ConfigurationFingerprint> fingerprints = new TreeMap<>();
    jdbc.query(
        "SELECT id, CAST(fingerprint AS text) AS fingerprint FROM backend_instances WHERE " + ALIVE,
        rs -> {
          fingerprints.put(rs.getObject("id", UUID.class), fingerprint(rs));
        },
        seconds(deadAfter));
    return fingerprints;
  }

  private List<LiveInstance> liveInstances(byte[] hmacKey) {
    return jdbc.query(
        "SELECT id, host_name, version, oldest_compatible_version, CAST(fingerprint AS text) AS"
            + " fingerprint FROM backend_instances WHERE "
            + ALIVE
            + " ORDER BY started_at, id",
        (rs, row) ->
            new LiveInstance(
                rs.getObject("id", UUID.class),
                rs.getString("host_name"),
                new InstanceVersion(
                    rs.getString("version"), rs.getString("oldest_compatible_version")),
                fingerprint(rs)),
        seconds(deadAfter));
  }

  /** The installation's HMAC key, created by the first instance that ever registers. */
  private byte[] hmacKey() {
    byte[] candidate = new byte[HMAC_KEY_BYTES];
    random.nextBytes(candidate);
    jdbc.update(
        "INSERT INTO backend_instance_fingerprint_key (id, hmac_key, created_at) VALUES (1, ?,"
            + " clock_timestamp()) ON CONFLICT (id) DO NOTHING",
        (Object) candidate);
    return jdbc.queryForObject(
        "SELECT hmac_key FROM backend_instance_fingerprint_key WHERE id = 1", byte[].class);
  }

  private static ConfigurationFingerprint fingerprint(ResultSet rs) throws SQLException {
    return new ConfigurationFingerprint(JSON.readValue(rs.getString("fingerprint"), DIGESTS));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }
}
