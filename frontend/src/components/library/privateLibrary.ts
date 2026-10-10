/** What a private library promises, wherever one is chosen or shown. */
export const PRIVATE_LIBRARY_NOTE = 'Nur Sie sehen diese Bibliothek.'

/** What „Sofort löschen“ removes and what stays - the consequence in its confirmation. */
export const PRIVATE_LIBRARY_ERASURE_CONSEQUENCE =
  'Gelöscht werden alle Dokumente, ihr Index, die abgelegten Originale, Ordner, Metadaten, Läufe ' +
  'und die Zuordnungen zu Spaces. ' +
  'In Ihren Chats heißen Belege aus dieser Bibliothek danach „Quelle entfernt“; der Text der Antworten bleibt stehen. ' +
  'Beim Anbieter bleiben Ihre Dateien unverändert.\n\n' +
  'Das lässt sich nicht rückgängig machen – auch nicht durch die Systemverwaltung.'

/** The state of a library marked for erasure, on its detail page. */
export const PRIVATE_LIBRARY_ERASING_NOTE =
  'Die Bibliothek wird nicht mehr aktualisiert, und ihre Dokumente lassen sich nicht mehr öffnen. ' +
  'Läuft noch eine Indexierung, endet sie beim nächsten Zugriff auf die Quelle; danach schließt die Löschung von selbst ab.'

/**
 * The run end QUOTA_EXHAUSTED in a few words, as the run list shows it. It names no quota: a private
 * library may have hit its own as well as its owner's, and the run protocol says which per file.
 */
export const QUOTA_EXHAUSTED_LABEL =
  'unvollständig: Speicherkontingent erschöpft, Dateien übersprungen'

/**
 * What frees space once a private library's run ended at a quota - either its own or the one its
 * owner's private libraries share. The run skips what does not fit and still reconciles, so
 * deleting at the provider or narrowing the source frees space with the next runs.
 */
export const QUOTA_EXHAUSTED_REMEDY =
  'Dateien, die nicht mehr passten, hat der Lauf übersprungen. Erschöpft ist entweder das ' +
  'Kontingent dieser Bibliothek oder das gemeinsame Kontingent Ihrer privaten Bibliotheken; ' +
  'welches, nennt das Laufprotokoll an jeder übersprungenen Datei. Platz in dieser Bibliothek ' +
  'schaffen Sie, indem Sie Dateien beim Anbieter löschen oder die Quelle eingrenzen. Für das ' +
  'gemeinsame Kontingent können Sie auch eine ganze private Bibliothek löschen („Sofort löschen“). ' +
  'Jeder weitere Lauf versucht die übersprungenen Dateien erneut und nimmt sie auf, sobald Platz ' +
  'ist. Sonst hilft eine höhere Grenze: Das Kontingent dieser Bibliothek legt der Betrieb fest, ' +
  'um eine höhere Grenze für Ihre privaten Bibliotheken bitten Sie die Systemverwaltung.'

/** What frees space once a shared library's quota is exhausted; it has only its own. */
export const LIBRARY_QUOTA_EXHAUSTED_REMEDY =
  'Dateien, die nicht mehr passten, hat der Lauf übersprungen. ' +
  'Platz schaffen Sie, indem Sie Dateien in der Quelle löschen oder die Quelle eingrenzen. ' +
  'Jeder weitere Lauf versucht die übersprungenen Dateien erneut und nimmt sie auf, sobald ' +
  'Platz ist. Sonst hilft ein höheres Kontingent je Bibliothek – das legt der Betrieb fest.'
