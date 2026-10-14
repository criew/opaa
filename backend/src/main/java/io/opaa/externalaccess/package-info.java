/**
 * The channel settings of the external access (ADR-0035, docs/features/external-access.md): the
 * installation-wide switch - default off and at the same time the emergency stop -, the token
 * lifetime ceiling, the per-token quota, the networks of the channel and the instructions text
 * handed to a foreign tool.
 *
 * <p>{@link io.opaa.externalaccess.ExternalAccessSettingsService} is the single entry point: it
 * validates a change, audits everything but the instructions text, and answers the per-call reads
 * ({@code isEnabled()}, {@code allowedCidrs()}) of the access paths. {@link
 * io.opaa.externalaccess.ExternalAccessNetworkPolicy} is the one place that decides whether a
 * client address belongs to the channel's networks.
 *
 * <p>{@code ExternalAccessSettingsController} exposes read and write path to {@code
 * SystemRole.SYSTEM_ADMIN} alone.
 */
package io.opaa.externalaccess;
