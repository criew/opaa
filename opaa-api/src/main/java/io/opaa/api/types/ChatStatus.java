package io.opaa.api.types;

/**
 * Single-value for now - the SHARED/WITHDRAWN axis from docs/features/spaces-and-assets.md#chats is
 * not built; the enum and its backing column exist so that axis can be added without a further
 * migration. The deletion of the chats of locked accounts takes only {@link #PRIVATE} chats ({@code
 * io.opaa.chat.LockedAccountChatCleanupStore}); a shared chat stays.
 */
public enum ChatStatus {
  PRIVATE
}
