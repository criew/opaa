package io.opaa.indexing.source;

import java.util.Objects;

/**
 * How a provider takes back a token the sign-in obtained: not at all, by the token revocation of
 * RFC 7009 (the refresh token, the client authenticated as for the token endpoint), or by an
 * authenticated {@code POST} carrying the access token as bearer (such as Dropbox).
 */
public sealed interface Revocation
    permits Revocation.None, Revocation.Rfc7009, Revocation.BearerPost {

  /** The endpoint revoking, {@code null} for none. */
  Endpoint endpoint();

  /** The provider offers no revocation; disconnecting only forgets the token. */
  record None() implements Revocation {

    @Override
    public Endpoint endpoint() {
      return null;
    }
  }

  /** RFC 7009 at {@code endpoint}. */
  record Rfc7009(Endpoint endpoint) implements Revocation {

    public Rfc7009 {
      Objects.requireNonNull(endpoint, "endpoint");
    }
  }

  /** A {@code POST} to {@code endpoint} with the access token as bearer and no body. */
  record BearerPost(Endpoint endpoint) implements Revocation {

    public BearerPost {
      Objects.requireNonNull(endpoint, "endpoint");
    }
  }
}
