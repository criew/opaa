package io.opaa.audit;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One procedure of the checksum chain (ADR-0059, Entscheidung 4), chosen by its name: {@code h0 =
 * SHA-256(name || LF || "YYYY-MM")}, {@code h_i = SHA-256(h_(i-1) || canonical(entry_i))} over
 * every column of {@code audit_log} in the order of {@link #COLUMNS}. A procedure never changes
 * once a month was sealed under it; a new column is a new procedure, chained from the next month
 * on.
 */
final class AuditChainHasher {

  /** The procedure new months start with. */
  static final String ALGORITHM = "OPAA-AUDIT-CHAIN-1";

  /**
   * The columns of {@code audit_log} in the order they enter the canonical form: NULL as one byte
   * {@code 0x00}, otherwise {@code 0x01}, the big-endian 32-bit length and the value - a uuid as 16
   * bytes, {@code recorded_at} as 8 bytes of epoch microseconds, {@code schema_version} as 2 bytes,
   * every other column as UTF-8.
   */
  static final List<String> COLUMNS =
      List.of(
          "event_id",
          "recorded_at",
          "organization_id",
          "actor_kind",
          "actor_ref",
          "event_type",
          "object_type",
          "object_id",
          "object_label",
          "subject_kind",
          "subject_ref",
          "before",
          "after",
          "outcome",
          "reason",
          "correlation_ref",
          "schema_version");

  /** The select list reading exactly {@link #COLUMNS} in their order. */
  static final String SELECT_COLUMNS = String.join(", ", COLUMNS);

  static final AuditChainHasher CURRENT = new AuditChainHasher(ALGORITHM);

  /** Every procedure this version can continue and verify, by name. */
  private static final Map<String, AuditChainHasher> ALGORITHMS = Map.of(ALGORITHM, CURRENT);

  private static final HexFormat HEX = HexFormat.of();

  private final String name;

  private AuditChainHasher(String name) {
    this.name = name;
  }

  /** The procedure named {@code algorithm}, empty for one this version does not know. */
  static Optional<AuditChainHasher> forAlgorithm(String algorithm) {
    return algorithm == null ? Optional.empty() : Optional.ofNullable(ALGORITHMS.get(algorithm));
  }

  String name() {
    return name;
  }

  byte[] start(YearMonth month) {
    return digest((name + "\n" + month).getBytes(StandardCharsets.UTF_8));
  }

  byte[] next(byte[] previous, ChainedEntry entry) {
    byte[] canonical = canonical(entry);
    ByteBuffer input = ByteBuffer.allocate(previous.length + canonical.length);
    input.put(previous).put(canonical);
    return digest(input.array());
  }

  static String hex(byte[] hash) {
    return HEX.formatHex(hash);
  }

  /** One row of {@link #SELECT_COLUMNS}, with the microsecond precision of {@code timestamptz}. */
  static ChainedEntry read(ResultSet rs) throws SQLException {
    return new ChainedEntry(
        rs.getObject("event_id", UUID.class),
        rs.getTimestamp("recorded_at").toInstant().truncatedTo(ChronoUnit.MICROS),
        rs.getObject("organization_id", UUID.class),
        rs.getString("actor_kind"),
        rs.getString("actor_ref"),
        rs.getString("event_type"),
        rs.getString("object_type"),
        rs.getString("object_id"),
        rs.getString("object_label"),
        rs.getString("subject_kind"),
        rs.getString("subject_ref"),
        rs.getString("before"),
        rs.getString("after"),
        rs.getString("outcome"),
        rs.getString("reason"),
        rs.getString("correlation_ref"),
        rs.getObject("schema_version", Short.class));
  }

  static byte[] canonical(ChainedEntry entry) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(512);
    field(out, uuid(entry.eventId()));
    field(out, micros(entry.recordedAt()));
    field(out, uuid(entry.organizationId()));
    field(out, utf8(entry.actorKind()));
    field(out, utf8(entry.actorRef()));
    field(out, utf8(entry.eventType()));
    field(out, utf8(entry.objectType()));
    field(out, utf8(entry.objectId()));
    field(out, utf8(entry.objectLabel()));
    field(out, utf8(entry.subjectKind()));
    field(out, utf8(entry.subjectRef()));
    field(out, utf8(entry.before()));
    field(out, utf8(entry.after()));
    field(out, utf8(entry.outcome()));
    field(out, utf8(entry.reason()));
    field(out, utf8(entry.correlationRef()));
    field(out, shortValue(entry.schemaVersion()));
    return out.toByteArray();
  }

  private static void field(ByteArrayOutputStream out, byte[] value) {
    if (value == null) {
      out.write(0);
      return;
    }
    out.write(1);
    out.writeBytes(ByteBuffer.allocate(4).putInt(value.length).array());
    out.writeBytes(value);
  }

  private static byte[] uuid(UUID value) {
    if (value == null) {
      return null;
    }
    return ByteBuffer.allocate(16)
        .putLong(value.getMostSignificantBits())
        .putLong(value.getLeastSignificantBits())
        .array();
  }

  private static byte[] micros(Instant value) {
    if (value == null) {
      return null;
    }
    long micros =
        Math.addExact(
            Math.multiplyExact(value.getEpochSecond(), 1_000_000L), value.getNano() / 1_000);
    return ByteBuffer.allocate(8).putLong(micros).array();
  }

  private static byte[] shortValue(Short value) {
    return value == null ? null : ByteBuffer.allocate(2).putShort(value).array();
  }

  private static byte[] utf8(String value) {
    return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] digest(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
    }
  }

  /** One entry of {@code audit_log} as the chain reads it: every column, as stored. */
  record ChainedEntry(
      UUID eventId,
      Instant recordedAt,
      UUID organizationId,
      String actorKind,
      String actorRef,
      String eventType,
      String objectType,
      String objectId,
      String objectLabel,
      String subjectKind,
      String subjectRef,
      String before,
      String after,
      String outcome,
      String reason,
      String correlationRef,
      Short schemaVersion) {}
}
