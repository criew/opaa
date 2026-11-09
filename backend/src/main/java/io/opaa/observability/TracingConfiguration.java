package io.opaa.observability;

import io.opaa.compliance.DataFlow;
import io.opaa.compliance.InstanceStateSource;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * The optional trace export (ADR-0071): off by default ({@code OPAA_TRACING_ENABLED}); switched on,
 * spans go over OTLP/HTTP to {@code OPAA_TRACING_OTLP_ENDPOINT}, every exporter wrapped in {@link
 * AllowlistedSpanExporter}. Switched on without an endpoint, the start fails instead of exporting
 * nowhere. Which routes leave no span: {@link UnobservedRoutes}.
 */
@DataFlow("trace-export")
@Configuration(proxyBeanMethods = false)
public class TracingConfiguration {

  static final String EXPORT_ENABLED = "management.tracing.export.enabled";
  static final String OTLP_ENDPOINT = "management.opentelemetry.tracing.export.otlp.endpoint";

  TracingConfiguration(Environment environment) {
    requireEndpointWhenEnabled(environment);
  }

  static void requireEndpointWhenEnabled(Environment environment) {
    if (environment.getProperty(EXPORT_ENABLED, Boolean.class, false)
        && !StringUtils.hasText(environment.getProperty(OTLP_ENDPOINT))) {
      throw new IllegalStateException(
          "OPAA_TRACING_ENABLED=true needs OPAA_TRACING_OTLP_ENDPOINT, the OTLP/HTTP address of"
              + " the trace collector (e.g. http://otel-collector:4318/v1/traces)");
    }
  }

  /** Whether this instance exports traces, for the disclosure. */
  @Bean
  InstanceStateSource traceExportState(Environment environment) {
    boolean enabled = environment.getProperty(EXPORT_ENABLED, Boolean.class, false);
    return new InstanceStateSource() {
      @Override
      public Map<String, Boolean> dataFlows(UUID organizationId) {
        return Map.of("trace-export", enabled);
      }
    };
  }

  @Bean
  static BeanPostProcessor allowlistedSpanExporters() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof SpanExporter exporter && !(bean instanceof AllowlistedSpanExporter)) {
          return new AllowlistedSpanExporter(exporter);
        }
        return bean;
      }
    };
  }
}
