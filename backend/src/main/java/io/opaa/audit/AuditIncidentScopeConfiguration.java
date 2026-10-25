package io.opaa.audit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the payload check of {@link AuditLogService}. */
@Configuration
@EnableConfigurationProperties(AuditPayloadProperties.class)
class AuditIncidentScopeConfiguration {}
