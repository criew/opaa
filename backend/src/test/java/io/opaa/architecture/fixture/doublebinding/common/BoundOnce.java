package io.opaa.architecture.fixture.doublebinding.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fixture.bound-once")
public record BoundOnce(int value) {}
