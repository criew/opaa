package io.opaa.permission;

import io.opaa.api.types.PermissionTransferScope;
import java.util.Set;

/**
 * What a transfer would move, shown before it is carried out - mandatory, and itself an audit event
 * even when the caller then abandons it (ADR-0036, Entscheidung 10).
 *
 * <p>{@code sourceLabel} is {@code null} whenever the source is a person: the preview of a
 * succession names the objects, never the person who left. {@code grantedAssets} and {@code spaces}
 * carry the distinct-object figures the sentence needs beside the row counts ("12 Berechtigungen an
 * 7 Bibliotheken").
 */
public record PermissionTransferPreview(
    PermissionSubject source,
    String sourceLabel,
    PermissionSubject target,
    String targetLabel,
    Set<PermissionTransferScope> scope,
    PermissionTransferCounts counts,
    int grantedAssets,
    int spaces) {}
