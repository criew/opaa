package io.opaa.indexing.job;

/**
 * Marks an unchecked failure that ends the run it is thrown in instead of counting as the failure
 * of the item being processed; {@link RunEndingFailures#rethrow} lets it pass every item catch.
 */
public interface EndsRun {}
