package io.opaa.query.toolloop;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the system limits of the agent loop. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentToolLoopProperties.class)
class AgentToolLoopConfiguration {}
