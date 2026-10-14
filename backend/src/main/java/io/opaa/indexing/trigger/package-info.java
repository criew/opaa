/**
 * The triggers of a run: by hand through {@link io.opaa.indexing.trigger.DocumentIndexingService},
 * on a library's schedule through {@link io.opaa.indexing.trigger.LibraryIndexingScheduler}. Both
 * choose the library and hand it to the executor registered for its source type. No connector uses
 * this package.
 */
package io.opaa.indexing.trigger;
