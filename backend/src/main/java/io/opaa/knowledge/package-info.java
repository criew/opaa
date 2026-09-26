/**
 * The holdings of a knowledge library: the library, its folders and documents with their
 * repositories, the access check, folder and storage-quota services, and the store of uploaded
 * originals. Sits below {@code io.opaa.indexing}, which fills the holdings, and below {@code
 * io.opaa.library}, which administers them; it names neither. What it needs from above it declares
 * as an interface the upper package implements ({@link io.opaa.knowledge.FolderDocumentDeleter}).
 */
package io.opaa.knowledge;
