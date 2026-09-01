/**
 * The DOCX/PPTX/ODT/ODP format pipelines (docs/features/ingestion-pipelines.md). ODT/ODP mirror
 * their DOCX/PPTX counterparts' cut but read {@code content.xml} directly through a hardened SAX
 * parser ({@link io.opaa.indexing.pipeline.office.OdfContentXml}) rather than Apache POI, which
 * never reads OpenDocument.
 */
package io.opaa.indexing.pipeline.office;
