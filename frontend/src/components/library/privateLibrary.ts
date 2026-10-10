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

/** The run end QUOTA_EXHAUSTED in a few words, as the run list shows it. */
export const QUOTA_EXHAUSTED_LABEL =
  'unvollständig: Speicherkontingent Ihrer privaten Bibliotheken erschöpft, Dateien übersprungen'

/**
 * What frees space once the personal quota is exhausted. The run skips what does not fit and still
 * reconciles, so deleting at the provider or narrowing the source frees space with the next runs.
 */
export const QUOTA_EXHAUSTED_REMEDY =
  'Dateien, die nicht mehr passten, hat der Lauf übersprungen. ' +
  'Platz schaffen Sie, indem Sie Dateien beim Anbieter löschen, die Quelle eingrenzen oder eine ' +
  'ganze private Bibliothek löschen („Sofort löschen“). Jeder weitere Lauf versucht die ' +
  'übersprungenen Dateien erneut und nimmt sie auf, sobald Platz ist. Sonst hilft eine höhere ' +
  'Grenze – darum bitten Sie die Systemverwaltung.'

/** The run end QUOTA_EXHAUSTED of a shared library, which has only its own quota. */
export const LIBRARY_QUOTA_EXHAUSTED_LABEL =
  'unvollständig: Speicherkontingent erschöpft, Dateien übersprungen'

/** What frees space once a shared library's quota is exhausted. */
export const LIBRARY_QUOTA_EXHAUSTED_REMEDY =
  'Dateien, die nicht mehr passten, hat der Lauf übersprungen. ' +
  'Platz schaffen Sie, indem Sie Dateien in der Quelle löschen oder die Quelle eingrenzen. ' +
  'Jeder weitere Lauf versucht die übersprungenen Dateien erneut und nimmt sie auf, sobald ' +
  'Platz ist. Sonst hilft ein höheres Kontingent je Bibliothek – das legt der Betrieb fest.'
