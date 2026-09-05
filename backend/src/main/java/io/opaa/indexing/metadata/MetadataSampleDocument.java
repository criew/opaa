package io.opaa.indexing.metadata;

import java.util.List;
import java.util.UUID;

/**
 * One sampled document with its Titelzeile and every stored value. Freie Schlagworte are not part
 * of it: they carry no claim the product stands behind and are not what the Stichprobe evaluates.
 */
public record MetadataSampleDocument(
    UUID documentId, String fileName, String title, List<MetadataSampleValue> values) {}
