package io.opaa.permission;

import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.PermissionTransferScope;
import java.util.Set;
import java.util.UUID;

/**
 * What a caller asks for: which subject's effects, of which kinds, go to which other subject. The
 * same order shape backs the preview and the execution, so the figures shown and the rows moved
 * answer the same question (ADR-0036, Entscheidung 10).
 */
public record PermissionTransferOrder(
    PermissionSubjectType sourceType,
    UUID sourceId,
    PermissionSubjectType targetType,
    UUID targetId,
    Set<PermissionTransferScope> scope) {}
