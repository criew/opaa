package io.opaa.api.types;

/**
 * Single-value for now (#525) - the SHARED/WITHDRAWN axis from
 * docs/features/spaces-and-assets.md#chats belongs to #205 and is deliberately not built here; the
 * enum and its backing column exist so that axis can be added later without a further migration.
 */
public enum ChatStatus {
  PRIVATE
}
