/**
 * Access to externally configured document sources (HTTP client construction, redirect following,
 * proxy/credentials parsing, bounded downloads and the byte ceilings behind them, SSRF
 * target-address validation, the politeness delay {@link io.opaa.sourceaccess.RequestPoliteness}
 * applies before a request to a source OPAA does not operate, and the {@link
 * io.opaa.sourceaccess.SourceRequestPolicy} every such request carries: {@code User-Agent} and how a
 * {@code 429} is waited out) - the single place every {@code indexing}/{@code library} caller that
 * fetches a source-configured URL goes through, instead of each maintaining its own copy. Depends on
 * neither {@code io.opaa.library} nor {@code io.opaa.api} - a source-access primitive has no
 * business knowing about a knowledge library or a generated DTO.
 */
package io.opaa.sourceaccess;
