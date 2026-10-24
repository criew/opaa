package io.opaa.instance;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * The invalidation bus (ADR-0053, Entscheidung 5). Its listening connection uses the address and
 * credentials of the pool, but is opened outside of it.
 */
@Configuration
public class InvalidationBusConfiguration {

  @Bean
  PostgresInvalidationBus invalidationBus(
      JdbcOperations jdbc,
      DataSource dataSource,
      JdbcConnectionDetails connectionDetails,
      ObjectProvider<InvalidationSubscriber> subscribers) {
    return new PostgresInvalidationBus(
        jdbc,
        dataSource,
        PostgresInvalidationBus.opener(
            connectionDetails.getJdbcUrl(),
            connectionDetails.getUsername(),
            connectionDetails.getPassword(),
            PostgresInvalidationBus.APPLICATION_NAME),
        subscribers,
        PostgresInvalidationBus.CHECK_INTERVAL);
  }
}
