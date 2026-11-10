package io.opaa.externalaccess;

import java.util.Optional;
import java.util.UUID;

/**
 * The person behind a personal access token, for the window {@link ExternalAccessQuota} keeps per
 * person besides the one per token: more tokens buy a person no more requests. The tokens live in
 * {@code io.opaa.externalaccess.token}, which implements this seam.
 */
public interface PersonalTokenOwners {

  /** The account {@code accessTokenId} was issued to; empty for an unknown or service token. */
  Optional<UUID> ownerOf(UUID accessTokenId);
}
