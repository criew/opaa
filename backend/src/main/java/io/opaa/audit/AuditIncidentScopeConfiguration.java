package io.opaa.audit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the deletion period of the grants of the anlassbezogene Klärung. */
@Configuration
@EnableConfigurationProperties(AuditIncidentScopeRetention.class)
class AuditIncidentScopeConfiguration {}
