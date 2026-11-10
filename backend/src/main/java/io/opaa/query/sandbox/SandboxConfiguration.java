package io.opaa.query.sandbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link SandboxProperties} while the sandbox is switched on. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "opaa.sandbox.enabled", havingValue = "true")
@EnableConfigurationProperties(SandboxProperties.class)
class SandboxConfiguration {}
