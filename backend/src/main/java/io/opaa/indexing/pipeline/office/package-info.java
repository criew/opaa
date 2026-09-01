/**
 * The DOCX/PPTX/ODT/ODP format pipelines (docs/features/ingestion-pipelines.md). ODT/ODP mirror
 * their DOCX/PPTX counterparts' cut but read {@code content.xml} directly through a hardened SAX
 * parser ({@link io.opaa.indexing.pipeline.office.OdfContentXml}) rather than Apache POI, which
 * never reads OpenDocument. {@link io.opaa.indexing.pipeline.office.OdfContentXml} is this
 * package's one public member, reused by {@code io.opaa.indexing.pipeline.tabular}'s ODS reader
 * (#1108) - every other class here stays package-private.
 */
package io.opaa.indexing.pipeline.office;
