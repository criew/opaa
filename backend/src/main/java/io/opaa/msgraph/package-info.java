/**
 * The Microsoft Graph client the Graph-based connectors share ({@link
 * io.opaa.msgraph.GraphClient}): JSON reads, paging by token, downloads from the pre-signed host
 * Graph redirects to, throttling and the failure kinds a caller acts on ({@link
 * io.opaa.msgraph.GraphException}). Every request goes through {@code io.opaa.sourceaccess}; the
 * token, the request budget and the meter come from the caller, so the package knows neither
 * indexing nor how a token is obtained.
 */
package io.opaa.msgraph;
