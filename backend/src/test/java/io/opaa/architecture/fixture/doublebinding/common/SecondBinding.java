package io.opaa.architecture.fixture.doublebinding.common;

import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties(BoundTwice.class)
abstract class SecondBinding {}
