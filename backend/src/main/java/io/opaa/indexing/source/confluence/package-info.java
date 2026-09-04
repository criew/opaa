/**
 * Access layer for Confluence as a knowledge source (ADR-0023): the port {@link
 * io.opaa.indexing.source.confluence.ConfluenceClient} with one adapter per edition ({@link
 * io.opaa.indexing.source.confluence.CloudConfluenceClient} against {@code /wiki/api/v2}, {@link
 * io.opaa.indexing.source.confluence.DataCenterConfluenceClient} against {@code /rest/api}), the
 * credential-free edition detection and the shared HTTP helper that honours {@code 429}/{@code
 * Retry-After} and never lets credentials reach a message or log.
 *
 * <p>Everything above this package - runs, content preparation, the connection test - works on the
 * edition-independent model this package returns and never inspects the edition itself; where an
 * edition difference matters, it belongs into the adapter.
 */
package io.opaa.indexing.source.confluence;
