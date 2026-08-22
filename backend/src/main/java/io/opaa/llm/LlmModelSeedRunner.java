package io.opaa.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link LlmModelSeeder#seedIfNeeded()} once, at application startup, before any request
 * can be served - the same shape {@link io.opaa.library.UploadPendingRecoveryRunner} already uses
 * for its own one-shot startup migration (#756).
 *
 * <p>Deliberately thin: the actual transactional seeding logic lives in {@link LlmModelSeeder}, a
 * separate bean, because {@code @Transactional} only takes effect on a call that goes through that
 * bean's own Spring proxy - a self-invocation from within this class would silently run without a
 * transaction.
 *
 * <p>Only {@link DataIntegrityViolationException} is treated as an expected, benign outcome:
 * multiple replicas can start at once and both see the takeover as not yet attempted; {@code
 * ux_llm_models_single_active} (migration 058) and {@code chk_llm_model_seed_marker_singleton}
 * (migration 060) let only one of them win, and the losing replica logs a warning and starts
 * normally rather than failing over a seed another instance already performed. Every other
 * exception (a missing/invalid {@code OPAA_SETTINGS_ENCRYPTION_KEY} while taking over a configured
 * {@code openai} API key, for instance) is a real, actionable failure and is left to propagate -
 * swallowing it here would turn a configuration problem an operator needs to see into a silent
 * no-op.
 */
@Component
public class LlmModelSeedRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(LlmModelSeedRunner.class);

  private final LlmModelSeeder seeder;

  public LlmModelSeedRunner(LlmModelSeeder seeder) {
    this.seeder = seeder;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      seeder.seedIfNeeded();
    } catch (DataIntegrityViolationException e) {
      log.warn(
          "Initiales Chat-Modell konnte nicht gespeichert werden - vermutlich hat eine andere"
              + " Instanz die Übernahme bereits durchgeführt",
          e);
    }
  }
}
