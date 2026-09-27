/**
 * The DOCX/PPTX/ODT/ODP file formats (docs/features/ingestion-pipelines.md). ODT/ODP mirror their
 * DOCX/PPTX counterparts' cut but read {@code content.xml} directly through a hardened SAX parser
 * ({@link io.opaa.format.file.office.OdfPackage}) rather than Apache POI, which never reads
 * OpenDocument. {@link io.opaa.format.file.office.OdfPackage} is the only type here made public
 * purely for reuse - by {@code io.opaa.format.file.tabular}'s ODS reader ; the package's other
 * public types are the format beans themselves. ODT/ODP additionally read {@code styles.xml} for
 * header/footer and master-slide text, DOCX every header/footer part through POI - all three
 * contribute that text as one deduplicated leading chunk via {@link
 * io.opaa.format.shared.RepeatingHeaderChunk}.
 */
package io.opaa.format.file.office;
