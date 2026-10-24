package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * What two instances starting together against an empty database do to its schema before they
 * serve: Flyway at the same moment, then - at the same moment again - Spring AI's vector store with
 * {@code initialize-schema} as the delivered application.yml sets it. Both must get through, and
 * {@code vector_store} must exist afterwards. Repeated, because a race of two DDL statements does
 * not lose every time.
 */
class VectorStoreColdStartTest extends AbstractMigrationTest {

  private static final boolean DELIVERED_INITIALIZE_SCHEMA = deliveredInitializeSchema();

  @Override
  protected List<String> baseFixtureMigrations() {
    return List.of();
  }

  // regression guard for #2944: initialize-schema=true raced in CREATE EXTENSION/CREATE TABLE
  @RepeatedTest(5)
  void twoInstancesPreparingAnEmptyDatabaseAtOnceBothGetThrough() throws Exception {
    CyclicBarrier together = new CyclicBarrier(2);
    ExecutorService instances = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> starts = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        starts.add(instances.submit(() -> prepare(together)));
      }
      for (Future<?> start : starts) {
        start.get(120, TimeUnit.SECONDS);
      }
    } finally {
      instances.shutdownNow();
    }

    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT to_regclass('vector_store') IS NOT NULL")) {
      rows.next();
      assertThat(rows.getBoolean(1)).isTrue();
    }
  }

  private Void prepare(CyclicBarrier together) throws Exception {
    try (Connection connection = connect()) {
      together.await();
      applyMigrations(connection, Migrations.all());
      together.await();
      PgVectorStore.builder(
              new JdbcTemplate(new SingleConnectionDataSource(connection, true)),
              mock(EmbeddingModel.class))
          .schemaName("public")
          .dimensions(1024)
          .indexType(PgIndexType.HNSW)
          .distanceType(PgDistanceType.COSINE_DISTANCE)
          .initializeSchema(DELIVERED_INITIALIZE_SCHEMA)
          .build()
          .afterPropertiesSet();
    }
    return null;
  }

  private static boolean deliveredInitializeSchema() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yml"));
    Properties properties = yaml.getObject();
    return Boolean.parseBoolean(
        properties.getProperty("spring.ai.vectorstore.pgvector.initialize-schema"));
  }
}
