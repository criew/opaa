/**
 * The file formats: one subpackage per format, each claiming its extensions through {@link
 * io.opaa.indexing.format.DocumentFormat#handledFormats()} and reached over a document's
 * <em>detected</em> content, never over its file name alone.
 *
 * <p>A file format never knows which source delivered the bytes. {@code fallback} claims nothing
 * and takes everything no other format claimed, which is why it is the only one whose absence would
 * change another format's routing.
 */
package io.opaa.indexing.format.file;
