package io.opaa.knowledge;

/**
 * What a storage quota says about taking in more bytes: within both quotas, past the quota of the
 * library, or past the quota across all private libraries of the library's owner.
 */
public enum QuotaVerdict {
  WITHIN,
  LIBRARY_EXHAUSTED,
  PERSON_EXHAUSTED
}
