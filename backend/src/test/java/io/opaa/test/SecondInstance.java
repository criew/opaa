package io.opaa.test;

import io.opaa.instance.CurrentInstance;
import io.opaa.instance.InstanceConfiguration;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A second instance next to the test context (ADR-0053): the instance register alone, over the test
 * context's database and with its whole environment, so it is admitted and writes its own
 * heartbeat. {@link #close} signs it off; {@link #fail} lets its heartbeat lapse the way a crashed
 * pod does, without signing off. Its row is deleted on close.
 */
public final class SecondInstance implements AutoCloseable {

  private final AnnotationConfigApplicationContext context;
  private final JdbcTemplate jdbc;
  private final UUID id;

  private SecondInstance(AnnotationConfigApplicationContext context, JdbcTemplate jdbc) {
    this.context = context;
    this.jdbc = jdbc;
    this.id = context.getBean(CurrentInstance.class).id();
  }

  /** Starts and registers the instance; the test context's own instance stays as it is. */
  public static SecondInstance start(
      ConfigurableEnvironment environment, DataSource dataSource, Clock clock) {
    return startCopy(copyOf(environment), dataSource, clock);
  }

  private static SecondInstance startCopy(
      ConfigurableEnvironment copy, DataSource dataSource, Clock clock) {
    DataSource shared = new DelegatingDataSource(dataSource);
    JdbcTemplate jdbc = new JdbcTemplate(shared);
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.setEnvironment(copy);
    context.registerBean(JdbcOperations.class, () -> jdbc);
    context.registerBean(
        PlatformTransactionManager.class, () -> new DataSourceTransactionManager(shared));
    context.registerBean(Clock.class, () -> clock);
    context.register(InstanceConfiguration.class);
    context.refresh();
    return new SecondInstance(context, jdbc);
  }

  /**
   * Starts and registers the instance with {@code properties} on top of the test context's
   * environment, as a pod whose environment differs in values the fingerprint does not cover.
   */
  public static SecondInstance startWith(
      ConfigurableEnvironment environment,
      DataSource dataSource,
      Clock clock,
      Map<String, Object> properties) {
    ConfigurableEnvironment copy = copyOf(environment);
    copy.getPropertySources().addFirst(new MapPropertySource("secondInstance", properties));
    return startCopy(copy, dataSource, clock);
  }

  public UUID id() {
    return id;
  }

  /**
   * Its heartbeat lies further back than any {@code dead-after}, as after a crash; the register
   * counts it as dead from now on, and its own next heartbeat finds it so.
   */
  public void fail() {
    jdbc.update(
        "UPDATE backend_instances SET heartbeat_at = clock_timestamp() - interval '1 day'"
            + " WHERE id = ?",
        id);
  }

  @Override
  public void close() {
    context.close();
    jdbc.update("DELETE FROM backend_instances WHERE id = ?", id);
  }

  private static ConfigurableEnvironment copyOf(ConfigurableEnvironment environment) {
    StandardEnvironment target = new StandardEnvironment();
    MutablePropertySources sources = target.getPropertySources();
    sources.forEach(source -> sources.remove(source.getName()));
    for (PropertySource<?> source : environment.getPropertySources()) {
      if (!source.getName().equals("configurationProperties")) {
        sources.addLast(source);
      }
    }
    ConfigurationPropertySources.attach(target);
    target.setActiveProfiles(environment.getActiveProfiles());
    return target;
  }
}
