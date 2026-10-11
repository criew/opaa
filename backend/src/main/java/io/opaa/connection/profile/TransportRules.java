package io.opaa.connection.profile;

/**
 * How requests travel to one target: with or without the certificate check. It belongs to the
 * target it was set for - the source's address - and never passes to another one, such as a token
 * endpoint.
 */
record TransportRules(boolean insecureSsl) {}
