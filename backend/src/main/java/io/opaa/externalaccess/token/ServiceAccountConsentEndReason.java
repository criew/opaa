package io.opaa.externalaccess.token;

/**
 * Why a row of a library's consent to a service account was closed. Mirrored by {@code
 * chk_service_account_library_consents_end_reason}; a consent whose end merely passed keeps its row
 * open until it is renewed or removed.
 */
public enum ServiceAccountConsentEndReason {
  /** The library administration took the consent back. */
  WITHDRAWN,
  /** The end was moved; the next row carries the consent from then on. */
  REPLACED,
  /** A consent that had already run out was renewed or removed; the row closes at its old end. */
  EXPIRED
}
