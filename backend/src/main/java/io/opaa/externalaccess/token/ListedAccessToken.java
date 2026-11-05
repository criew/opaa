package io.opaa.externalaccess.token;

import io.opaa.api.types.ExternalAccessTokenStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * What the administration's Bestandsliste shows of a token, personal or of a service account - and
 * deliberately nothing about its use.
 */
public interface ListedAccessToken {

  UUID getId();

  String getName();

  Instant getCreatedAt();

  Instant getExpiresAt();

  ExternalAccessTokenStatus status(Instant now);
}
