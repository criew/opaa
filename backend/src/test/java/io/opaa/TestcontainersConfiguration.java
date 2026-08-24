package io.opaa;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared {@code pgvector/pgvector:pg18} Testcontainers datasource for integration tests that need a
 * real Postgres instance instead of Hibernate-generated schema. {@code public} so it can be reused
 * across packages via {@code @Import(TestcontainersConfiguration.class)} instead of duplicating the
 * container definition per test class - see #288.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  public PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));
  }
}
