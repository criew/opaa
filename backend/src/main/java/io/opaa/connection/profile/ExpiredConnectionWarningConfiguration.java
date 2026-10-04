package io.opaa.connection.profile;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ExpiredConnectionWarningProperties.class)
class ExpiredConnectionWarningConfiguration {}
