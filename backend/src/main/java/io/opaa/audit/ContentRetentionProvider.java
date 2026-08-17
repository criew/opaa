package io.opaa.audit;

import java.util.Optional;

/**
 * Extension point for {@link AuditRetentionSettingsService}'s consistency check (#395,
 * docs/features/security-and-compliance.md#aufbewahrung): "Die Protokollfrist muss mindestens so
 * lang gewählt werden wie die Aufbewahrung der Inhalte, auf die sie sich bezieht ... Das Produkt
 * warnt bei einer inkonsistenten Einstellung."
 *
 * <p><b>No implementation of this interface exists yet.</b> Content retention (Chats, Artefakte,
 * private Inhalte) is #216's own, later scope, deliberately not built here - #395's acceptance
 * criteria only require that a shorter protocol retention than content retention produce a warning,
 * not that content retention itself exist yet. {@link
 * AuditRetentionSettingsService#updateRetention} looks this bean up via {@code
 * ObjectProvider<ContentRetentionProvider>} (zero-or-one, never required), and simply cannot warn
 * while no bean is registered - there is nothing to compare against. Once #216 introduces a content
 * retention setting, it registers exactly one {@link org.springframework.stereotype.Component}
 * implementing this interface and the warning starts firing without any change here.
 */
public interface ContentRetentionProvider {

  /**
   * The currently configured content retention, in months, or empty if content retention is itself
   * unconfigured (nothing to warn against).
   */
  Optional<Integer> contentRetentionMonths();
}
