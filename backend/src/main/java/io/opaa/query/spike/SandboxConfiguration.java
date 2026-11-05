package io.opaa.query.spike;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link SandboxProperties} while both switches of {@link RunCodeTool} are on. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {"opaa.spike.tool-loop.enabled", "opaa.sandbox.enabled"},
    havingValue = "true")
@EnableConfigurationProperties(SandboxProperties.class)
class SandboxConfiguration {}
