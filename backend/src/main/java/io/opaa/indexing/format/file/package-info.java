/**
 * The file formats: one subpackage per format, each declaring the extensions and media types it
 * admits through {@link io.opaa.indexing.format.DocumentFormat#admittedFormats()} and reached over
 * a document's <em>detected</em> content, never over its file name alone.
 *
 * <p>A file format never knows which source delivered the bytes. {@code fallback} claims nothing
 * for routing and takes everything no other format claimed, which is why it is the only one whose
 * absence would change another format's routing - it admits {@code .txt} and {@code .doc} all the
 * same, the two formats accepted without one of their own.
 */
package io.opaa.indexing.format.file;
