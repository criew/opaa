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
  'unvollständig: Speicherkontingent Ihrer privaten Bibliotheken erschöpft'

/**
 * What frees space once the personal quota is exhausted. A run ends before its reconciliation
 * there, so deleting at the provider or narrowing the source frees nothing; only erasing a whole
 * private library or a higher limit does.
 */
export const QUOTA_EXHAUSTED_REMEDY =
  'Platz schaffen Sie, indem Sie eine ganze private Bibliothek löschen („Sofort löschen“). ' +
  'Sonst hilft nur eine höhere Grenze – darum bitten Sie die Systemverwaltung. ' +
  'Dateien beim Anbieter zu löschen oder die Quelle einzugrenzen schafft derzeit keinen Platz.'
