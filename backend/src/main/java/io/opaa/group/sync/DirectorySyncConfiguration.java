package io.opaa.group.sync;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The synchronisation policy's own configuration. The productive {@link DirectoryClient} is wired
 * by {@code io.opaa.group.sync.connector.DirectoryConnectorConfiguration}, one connector per
 * provider (#1817).
 */
@Configuration
@EnableConfigurationProperties(DirectorySyncProperties.class)
public class DirectorySyncConfiguration {}
