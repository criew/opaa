package io.opaa.account;

import java.time.Duration;

/**
 * The lockout after failed sign-ins in force (ADR-0033, Entscheidung 9; ADR-0060, Entscheidung 17):
 * the account is locked once its counter reaches {@code maxAttempts}, for {@code duration}. Always
 * within the limits of the operation, whatever the settings row holds.
 */
public record LocalLockoutPolicy(int maxAttempts, Duration duration) {}
