package io.opaa.connection.request;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProfileRequestProperties.class)
class ProfileRequestConfiguration {}
