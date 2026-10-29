package io.opaa.config;

import io.opaa.knowledge.VectorTable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when a vector index of the vector table was built for another distance than
 * {@code spring.ai.vectorstore.pgvector.distance-type}: the search would no longer use it and scan
 * the whole table, and a re-embedding would build the next index for the other one. A changed value
 * only takes effect with a rebuilt index. Without a vector table, before the first embedding model
 * is installed, there is nothing to compare; a table created later takes the configured distance.
 */
@Component
public class VectorIndexDistanceGuard implements ApplicationRunner {

  static final String VARIABLE = "OPAA_PGVECTOR_DISTANCE_TYPE";

  private final JdbcTemplate jdbcTemplate;
  private final VectorTable table;
  private final PgDistanceType distanceType;

  public VectorIndexDistanceGuard(
      JdbcTemplate jdbcTemplate, VectorTable table, PgVectorStoreProperties pgVector) {
    this.jdbcTemplate = jdbcTemplate;
    this.table = table;
    this.distanceType = pgVector.getDistanceType();
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!table.existsIn(jdbcTemplate)) {
      return;
    }
    for (VectorIndex index : vectorIndexes()) {
      if (!index.operatorClass().equals(distanceType.index)) {
        throw new IllegalStateException(mismatch(index));
      }
    }
  }

  /** One vector index of the table and the operator class of its vector column. */
  record VectorIndex(String name, String operatorClass) {}

  /** The HNSW and IVFFlat indexes of the table: the two methods pgvector searches by distance. */
  private List<VectorIndex> vectorIndexes() {
    return jdbcTemplate.query(
        "SELECT ic.relname AS index_name, opc.opcname AS operator_class"
            + " FROM pg_index i"
            + " JOIN pg_class ic ON ic.oid = i.indexrelid"
            + " JOIN pg_am am ON am.oid = ic.relam"
            + " JOIN pg_opclass opc ON opc.oid = ANY (i.indclass::oid[])"
            + " WHERE i.indrelid = to_regclass(?) AND am.amname IN ('hnsw', 'ivfflat')"
            + " ORDER BY ic.relname",
        (rs, row) -> new VectorIndex(rs.getString("index_name"), rs.getString("operator_class")),
        table.qualifiedName());
  }

  private String mismatch(VectorIndex index) {
    String configured = setting(distanceType);
    String built =
        Arrays.stream(PgDistanceType.values())
            .filter(type -> type.index.equals(index.operatorClass()))
            .findFirst()
            .map(VectorIndexDistanceGuard::setting)
            .orElse(index.operatorClass());
    return ("%s (spring.ai.vectorstore.pgvector.distance-type) is %s, but the vector index %s of %s"
            + " was built for %s (%s): the search would not use it. Set %s back to %s, or rebuild"
            + " the index with %s; see docs/handbuch/betrieb/datenbank.md.")
        .formatted(
            VARIABLE,
            configured,
            index.name(),
            table.qualifiedName(),
            built,
            index.operatorClass(),
            VARIABLE,
            built,
            distanceType.index);
  }

  /** The value of {@link #VARIABLE} that selects {@code type}. */
  private static String setting(PgDistanceType type) {
    return type.name().toLowerCase(Locale.ROOT);
  }
}
