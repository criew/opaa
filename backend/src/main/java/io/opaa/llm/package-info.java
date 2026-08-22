/**
 * Managed chat models - Stufe 1 of the model management epic (#755,
 * docs/features/llm-integration.md#stufe-1-verwaltete-chat-modelle-in-umsetzung): a list of chat
 * models with exactly one systemwide active entry, replacing the environment-variable-only
 * configuration ({@code spring.ai.model.chat} plus the {@code ollama}/{@code openai} blocks in
 * {@code application.yml}) that decided this before #756.
 *
 * <p>{@link io.opaa.llm.LlmModelService} is the single entry point for reading and changing models,
 * modelled after {@code io.opaa.branding.BrandingSettingsService} - encryption of the optional API
 * key ({@link io.opaa.security.SettingsEncryptor}) happens here, before the database sees anything,
 * and every change records an audit event. {@link io.opaa.llm.LlmModelSeeder} (triggered once at
 * startup by {@link io.opaa.llm.LlmModelSeedRunner}) performs the one-time takeover of an existing
 * installation's environment configuration as the initial active model, so an upgrade never leaves
 * a deployment without one - guarded by the permanent {@link io.opaa.llm.LlmModelSeedMarker}, not
 * by whether {@code llm_models} happens to be empty.
 *
 * <p>#756 covers persistence, encryption and the seed migration only; the REST endpoints under
 * {@code /api/v1/admin/models} follow in #757, and the chat call path that actually resolves the
 * active model at runtime follows in #758.
 */
package io.opaa.llm;
