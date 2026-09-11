package io.opaa.mail;

import io.opaa.api.types.MailEncryption;

/**
 * The administrable SMTP fields as one replacement (#1536) - the domain parameter object {@code
 * io.opaa.api.SystemMailSettingsController} hands to {@link MailSettingsService}, so no service
 * method grows a nine-argument signature and no service sees an {@code io.opaa.api.dto} type
 * (AGENTS.md, "Domain-Services kennen keine io.opaa.api.dto-Typen").
 *
 * @param password three-way, and the only field that is: {@code null} or the literal {@link
 *     MailSettingsService#PASSWORD_MASK} leaves the stored password untouched, an empty string
 *     clears it, anything else replaces it. A secret never travels back to the server merely
 *     because a form rendered its mask.
 */
public record MailSettingsUpdate(
    boolean enabled,
    String host,
    Integer port,
    String username,
    String password,
    MailEncryption encryption,
    String fromAddress,
    String fromName) {}
