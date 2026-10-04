package io.opaa.connection.profile;

/**
 * How requests travel to one target: through {@code proxy} ({@code null} for none) and with or
 * without the certificate check. They belong to the target they were set for - the source's address
 * - and never pass to another one, such as a token endpoint.
 */
record TransportRules(String proxy, boolean insecureSsl) {}
