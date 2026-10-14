package io.opaa.succession;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the module's configuration properties. */
@Configuration
@EnableConfigurationProperties(SuccessionProperties.class)
public class SuccessionConfiguration {}
