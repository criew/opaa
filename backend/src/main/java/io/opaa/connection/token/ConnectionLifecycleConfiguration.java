package io.opaa.connection.token;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the lifecycle settings of connections; the application class cannot name this module. */
@Configuration
@EnableConfigurationProperties({
  ConnectionLifecycleProperties.class,
  PrivateLibraryDeletionPeriod.class
})
class ConnectionLifecycleConfiguration {}
