package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * What two instances starting together against an empty database do to its schema before they
 * serve: Flyway at the same moment, then - at the same moment again - the creation of {@code
 * vector_store} with the dimension of the installed embedding model, each in its own transaction
 * under the schema lock. Both must get through, and {@code vector_store} must exist once
 * afterwards, with the dimension. Repeated, because a race of two DDL statements does not lose
 * every time.
 */
class VectorStoreColdStartTest extends AbstractMigrationTest {

  private static final int DIMENSIONS = 768;

  @Override
  protected List<String> baseFixtureMigrations() {
    return List.of();
  }

  // regression guard for #2944: two instances racing in CREATE EXTENSION/CREATE TABLE
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
        ResultSet rows =
            statement.executeQuery(
                "SELECT atttypmod FROM pg_attribute WHERE attrelid = to_regclass('vector_store')"
                    + " AND attname = 'embedding'")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isEqualTo(DIMENSIONS);
    }
  }

  private Void prepare(CyclicBarrier together) throws Exception {
    try (Connection connection = connect()) {
      together.await();
      Migrations.applyAsTheApplication(
          connection.getMetaData().getURL(),
          connection.getMetaData().getUserName(),
          POSTGRES.getPassword(),
          connection.getSchema(),
          Migrations.all());
      together.await();
      connection.setAutoCommit(false);
      Migrations.createVectorTable(connection, connection.getSchema(), DIMENSIONS);
      connection.commit();
    }
    return null;
  }

  /**
   * The table comes from the installation only: Spring AI must not create it at the start of an
   * instance, which raced between instances starting together.
   */
  @Test
  void theDeliveredConfigurationLetsNoInstanceCreateTheTableAtStartUp() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yml"));
    Properties properties = yaml.getObject();

    assertThat(properties.getProperty("spring.ai.vectorstore.pgvector.initialize-schema"))
        .isEqualTo("false");
  }
}
