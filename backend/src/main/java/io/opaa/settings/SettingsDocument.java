package io.opaa.settings;

import java.time.Instant;
import java.util.Map;

/**
 * A settings document: {@link SettingsTransferService#FORMAT}, its version, when it was exported
 * and the values of each section by section key. Read from a file, every part may be missing or of
 * the wrong kind; {@link SettingsTransferService} checks it before anything is applied.
 */
public record SettingsDocument(
    String format,
    Integer formatVersion,
    Instant exportedAt,
    Map<String, Map<String, Object>> sections) {}
