package io.opaa.searchadmin;

/**
 * One model role's operational state.
 *
 * <p><b>Carries no access key, in any form.</b> The record has no field for one, so no mapper can
 * accidentally pass one on - the same write-only convention {@code LlmModelResponse} establishes
 * for the model administration.
 *
 * @param endpoint base address, or {@code null} while the role is unbelegt.
 * @param modelIdentifier model name, or {@code null} while the role is unbelegt.
 * @param detail the German explanation shown next to the state. Never a raw exception message: a
 *     technical cause belongs in the log, not on an administration page.
 */
public record ModelRoleStatus(
    ModelRole role,
    ModelRoleCondition condition,
    String endpoint,
    String modelIdentifier,
    String detail) {}
