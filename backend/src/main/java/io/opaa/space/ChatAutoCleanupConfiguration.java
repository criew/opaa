package io.opaa.space;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the periods of the automatic chat cleanup (#1923). */
@Configuration
@EnableConfigurationProperties(ChatAutoCleanupProperties.class)
class ChatAutoCleanupConfiguration {}
