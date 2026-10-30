package io.opaa.audit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the payload check of {@link AuditLogService} and the bound of the anlassbezogene Klärung.
 */
@Configuration
@EnableConfigurationProperties({AuditPayloadProperties.class, AuditIncidentScopeProperties.class})
class AuditIncidentScopeConfiguration {}
