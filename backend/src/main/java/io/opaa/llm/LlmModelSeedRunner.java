package io.opaa.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link LlmModelSeeder#seedIfNeeded()} once at start-up, called by {@code
 * io.opaa.installation.InstallationStartup} only when the installation is completed (ADR-0060): a
 * pending installation takes its chat model from the installation, never from the environment.
 *
 * <p>Deliberately thin: the actual transactional seeding logic lives in {@link LlmModelSeeder}, a
 * separate bean, because {@code @Transactional} only takes effect on a call that goes through that
 * bean's own Spring proxy - a self-invocation from within this class would silently run without a
 * transaction.
 *
 * <p>Only {@link DataIntegrityViolationException} is treated as an expected, benign outcome:
 * multiple replicas can start at once and both see the takeover as not yet attempted; {@code
 * ux_llm_models_single_active} and {@code chk_llm_model_seed_marker_singleton} let only one of them
 * win, and the losing replica logs a warning and starts normally rather than failing over a seed
 * another instance already performed. Every other exception is a real, actionable failure and is
 * left to propagate.
 *
 * <p>A missing {@code OPAA_SETTINGS_ENCRYPTION_KEY} while taking over a configured {@code openai}
 * API key is deliberately <em>not</em> among those: {@link LlmModelSeeder#seedIfNeeded()} catches
 * that case itself and logs an operator-facing error instead of throwing, so the application still
 * starts - see that method's own Javadoc. Only this class ever needs to distinguish "startup may
 * proceed" from "startup must abort"; {@link LlmModelSeeder} never decides that for itself beyond
 * this one case.
 *
 * <p><b>Ablaufdatum:</b> einmalige Übernahme für Bestandsinstallationen, Kandidat zur Entfernung ab
 * v1.0.
 */
@Component
public class LlmModelSeedRunner {

  private static final Logger log = LoggerFactory.getLogger(LlmModelSeedRunner.class);

  private final LlmModelSeeder seeder;

  public LlmModelSeedRunner(LlmModelSeeder seeder) {
    this.seeder = seeder;
  }

  public void runStartupSeed() {
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
