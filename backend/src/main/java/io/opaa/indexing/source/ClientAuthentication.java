package io.opaa.indexing.source;

/** How a client proves its secret at a token endpoint (RFC 6749, 2.3.1). */
public enum ClientAuthentication {
  /** Client id and secret in an HTTP Basic {@code Authorization} header. */
  CLIENT_SECRET_BASIC,
  /** Client id and secret as form fields of the request body. */
  CLIENT_SECRET_POST
}
