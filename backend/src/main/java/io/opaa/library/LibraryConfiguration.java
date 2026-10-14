package io.opaa.library;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the module's configuration properties. */
@Configuration
@EnableConfigurationProperties({
  AttachmentExtractionProperties.class,
  ExternalAccessProperties.class
})
public class LibraryConfiguration {}
