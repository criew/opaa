/**
 * Export and import of the installation's settings (ADR-0060, Entscheidung 12). Every setting the
 * system administration decides belongs to exactly one {@link io.opaa.settings.SettingsSection}, a
 * bean in the package of the module that owns it; {@link io.opaa.settings.SettingsTransferService}
 * collects them into one versioned document without secrets and applies such a document through
 * each section's regular write path, so validation, limits of the operation and audit events are
 * those of the administration.
 */
package io.opaa.settings;
