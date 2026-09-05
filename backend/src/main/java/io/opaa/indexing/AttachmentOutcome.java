package io.opaa.indexing;

/**
 * How one attachment the shared attachment path met ended: indexed as a document of its own,
 * skipped without an attempt (unchanged, unsupported, rejected by a limit or policy), or attempted
 * and failed (download, read or processing error, quota).
 */
public enum AttachmentOutcome {
  PROCESSED,
  SKIPPED,
  FAILED
}
