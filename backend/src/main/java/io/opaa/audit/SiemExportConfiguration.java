package io.opaa.audit;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** The SIEM export of the audit log ({@link SiemExportProperties}); off by default (ADR-0055). */
@Configuration
@EnableConfigurationProperties(SiemExportProperties.class)
public class SiemExportConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SiemExportConfiguration.class);

  @Bean
  @ConditionalOnProperty(prefix = "opaa.siem-export", name = "enabled", havingValue = "true")
  SiemExportService siemExportService(
      JdbcTemplate jdbc,
      SiemExportProperties properties,
      AuditRetentionSettingsRepository retention,
      MeterRegistry meterRegistry,
      ObjectProvider<BuildProperties> buildProperties) {
    log.info(
        "SIEM export on: syslog/CEF to {}:{} {}",
        properties.host(),
        properties.port(),
        properties.tls() ? "with TLS" : "without TLS");
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
        retention,
        meterRegistry);
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
