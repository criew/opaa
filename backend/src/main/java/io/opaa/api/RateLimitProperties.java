package io.opaa.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opaa.rate-limit")
public record RateLimitProperties(boolean enabled, EndpointLimit query, EndpointLimit indexing) {

  public record EndpointLimit(int maxRequests, int windowSeconds) {}
}
