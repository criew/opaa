/**
 * Access to externally configured document sources (HTTP client construction, redirect following,
 * proxy/credentials parsing, bounded downloads, SSRF target-address validation) - the single place
 * every {@code indexing}/{@code library} caller that fetches a source-configured URL goes through,
 * instead of each maintaining its own copy (#876, Epic #826 finding B7). Depends on neither {@code
 * io.opaa.library} nor {@code io.opaa.api} - a source-access primitive has no business knowing
 * about a knowledge library or a generated DTO.
 */
package io.opaa.sourceaccess;
