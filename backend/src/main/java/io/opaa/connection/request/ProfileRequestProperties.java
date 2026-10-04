package io.opaa.connection.request;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The budget of connection profile requests per person: new requests within one hour, and requests
 * open at the same time. Independent of {@code opaa.rate-limit.enabled}: both bound what one person
 * can put on the system administration's list, not the load of an endpoint.
 *
 * @param maxPerHour new requests per person within any hour (default 5, at least 1)
 * @param maxOpen open requests per person (default 10, at least 1)
 */
@ConfigurationProperties(prefix = "opaa.connection.profile-requests")
public record ProfileRequestProperties(Integer maxPerHour, Integer maxOpen) {

  public ProfileRequestProperties {
    maxPerHour = maxPerHour == null ? 5 : maxPerHour;
    maxOpen = maxOpen == null ? 10 : maxOpen;
    if (maxPerHour < 1 || maxOpen < 1) {
      throw new IllegalArgumentException(
          "opaa.connection.profile-requests.max-per-hour and max-open must be at least 1");
    }
  }
}
