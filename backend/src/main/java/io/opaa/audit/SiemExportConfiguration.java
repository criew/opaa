package io.opaa.audit;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The SIEM export of the audit log ({@link SiemExportProperties}); off by default (ADR-0055). The
 * service exists only while switched on; an instance starting with it switched off marks the
 * position, so the export begins anew when switched on again (Entscheidung 5).
 */
@Configuration
@EnableConfigurationProperties(SiemExportProperties.class)
public class SiemExportConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SiemExportConfiguration.class);

  @Bean
  @ConditionalOnProperty(prefix = "opaa.siem-export", name = "enabled", havingValue = "true")
  SiemExportService siemExportService(
      JdbcTemplate jdbc,
      SiemExportProperties properties,
      MeterRegistry meterRegistry,
      ObjectProvider<BuildProperties> buildProperties,
      ObjectProvider<PersonalAuditObjects> personalObjects) {
    log.info(
        "SIEM export on: syslog/CEF to {}:{} {}, object names {}",
        properties.host(),
        properties.port(),
        properties.tls() ? "with TLS" : "without TLS",
        properties.includeLabels() ? "included" : "left out");
    if (!properties.tls()) {
      log.warn("opaa.siem-export.tls=false: the audit log leaves the installation unencrypted");
    }
    String version =
        buildProperties.stream().map(BuildProperties::getVersion).findFirst().orElse(null);
    return new SiemExportService(
        jdbc,
        properties,
        new SyslogSender(
            properties.host(), properties.port(), properties.tls(), properties.timeout()),
        new CefSyslogFormatter(hostname(), version),
        personalObjects.orderedStream().toList(),
        meterRegistry);
  }

  /**
   * Start action of an instance with the export switched off (ADR-0053, Datenbank): idempotent, it
   * only stamps the existing position once.
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "opaa.siem-export",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  ApplicationRunner siemExportDisabledMarker(JdbcTemplate jdbc) {
    return arguments -> SiemExportService.markDisabled(jdbc);
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
