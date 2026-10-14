package io.opaa.architecture.fixture.doublebinding.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fixture.bound-twice")
public record BoundTwice(int value) {}
