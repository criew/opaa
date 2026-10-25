package io.opaa.chat;

/**
 * The PDF export of one chat.
 *
 * @param fileName the suggested download name, derived from the chat's title, ending in {@code
 *     .pdf}
 */
public record ChatPdf(String fileName, byte[] content) {}
