package io.opaa.permission;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the module's configuration properties. */
@Configuration
@EnableConfigurationProperties(GroupSizeProperties.class)
public class PermissionConfiguration {}
