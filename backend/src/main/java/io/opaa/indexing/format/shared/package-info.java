/**
 * Building blocks more than one format reuses: XHTML event reading, heading-section cutting, table
 * rendering, whitespace and title-line normalisation, and the deduplicated header chunk.
 *
 * <p>Everything here is stateless and format-agnostic - it knows no reader, no bean and no {@link
 * io.opaa.indexing.format.DocumentFormat}. Both {@code file} and {@code stream} formats use it; it
 * uses neither.
 */
package io.opaa.indexing.format.shared;
