package io.opaa.chat;

/** An imported chat as its author lists it, with the key the import gave it. */
public record ImportedChatEntry(String importKey, ChatListEntry chat) {}
