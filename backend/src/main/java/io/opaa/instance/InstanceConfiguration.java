package io.opaa.instance;

import io.opaa.compliance.InstanceDisclosureAssembler;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The instance register and this process in it (ADR-0053, Entscheidungen 3 und 4). Self-contained,
 * so that a second context against the same database can be started with nothing but a data source,
 * a transaction manager and a clock.
 */
@Configuration
@EnableConfigurationProperties(InstanceProperties.class)
public class InstanceConfiguration {

  @Bean
  InstanceRegistry instanceRegistry(
      JdbcOperations jdbc, PlatformTransactionManager transactions, InstanceProperties properties) {
    return new InstanceRegistry(jdbc, new TransactionTemplate(transactions), properties);
  }

  @Bean
  CurrentInstance currentInstance(
      InstanceRegistry registry,
      Environment environment,
      Clock clock,
      ObjectProvider<BuildProperties> buildProperties,
      InstanceProperties properties) {
    String version =
        buildProperties.stream()
            .map(BuildProperties::getVersion)
            .findFirst()
            .orElse(InstanceDisclosureAssembler.DEVELOPMENT_VERSION);
    return new CurrentInstance(
        registry,
        new ConfigurationFingerprintReader(environment, clock),
        InstanceVersion.of(version),
        hostName(),
        properties);
  }

  @Bean
  RetiredRetentionVariables retiredRetentionVariables(
      InstanceRegistry registry, CurrentInstance currentInstance, Environment environment) {
    return new RetiredRetentionVariables(registry, currentInstance, environment);
  }

  @Bean
  InstanceRegisterState instanceRegisterState(InstanceRegistry registry) {
    return new InstanceRegisterState(registry);
  }

  /** The pod or host name, for display only; {@code unbekannt} when neither can be read. */
  static String hostName() {
    String fromEnvironment = System.getenv("HOSTNAME");
    if (fromEnvironment != null && !fromEnvironment.isBlank()) {
      return fromEnvironment.trim();
    }
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "unbekannt";
    }
  }
}
