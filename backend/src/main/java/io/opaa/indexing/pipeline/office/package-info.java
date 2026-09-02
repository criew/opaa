/**
 * The DOCX/PPTX/ODT/ODP format pipelines (docs/features/ingestion-pipelines.md). ODT/ODP mirror
 * their DOCX/PPTX counterparts' cut but read {@code content.xml} directly through a hardened SAX
 * parser ({@link io.opaa.indexing.pipeline.office.OdfContentXml}) rather than Apache POI, which
 * never reads OpenDocument. {@link io.opaa.indexing.pipeline.office.OdfContentXml} is the only type
 * here made public purely for reuse - by {@code io.opaa.indexing.pipeline.tabular}'s ODS reader
 * (#1108); the package's other public types are the pipeline beans themselves. ODT/ODP additionally
 * read {@code styles.xml} for header/footer and master-slide text, DOCX its own default
 * header/footer parts through POI - all three contribute that text as one deduplicated leading
 * chunk via {@link io.opaa.indexing.pipeline.RepeatingHeaderChunk} (#1145).
 */
package io.opaa.indexing.pipeline.office;
