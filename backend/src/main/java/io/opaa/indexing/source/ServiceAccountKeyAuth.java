package io.opaa.indexing.source;

import java.net.URI;
import java.util.Objects;

/**
 * The sign-in kind "Dienstkonto-Schlüssel" a connector offers (ADR-0040, Entscheidung 2): the core
 * signs a JWT assertion (RFC 7523) with the stored key, sends it to {@code tokenEndpoint} - the
 * only target outside {@code sourceUrl} key material may reach - and hands the connector the access
 * token alone.
 */
public record ServiceAccountKeyAuth(URI tokenEndpoint, String scope) {

  public ServiceAccountKeyAuth {
    Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
    Objects.requireNonNull(scope, "scope");
  }
}
