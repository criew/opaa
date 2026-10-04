package io.opaa.connection.token;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the lifecycle setting of connections; the application class cannot name this module. */
@Configuration
@EnableConfigurationProperties(ConnectionLifecycleProperties.class)
class ConnectionLifecycleConfiguration {}
