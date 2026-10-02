package io.opaa.space;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the periods of the automatic chat cleanup. */
@Configuration
@EnableConfigurationProperties(ChatAutoCleanupProperties.class)
class ChatAutoCleanupConfiguration {}
