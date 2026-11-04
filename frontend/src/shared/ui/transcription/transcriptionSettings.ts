/**
 * The language rule and the texts of the transcription model beyond `MODEL_ROLE_TEXTS`, shared by
 * the model administration („Modelle → Spracheingabe“) and the installation step; the backend
 * applies the same rule (`TranscriptionEndpoint`).
 */
export const TRANSCRIPTION_TEXTS = {
  externalService:
    'Aufnahmen gehen an einen externen Dienst außerhalb des Hauses. Das braucht eine eigene datenschutzrechtliche Prüfung.',
  languageLabel: 'Sprache',
  languageHint: 'Kürzel aus zwei Buchstaben, etwa de; leer erkennt das Modell die Sprache selbst.',
  languageRule:
    'Die Sprache ist ein Kürzel aus zwei Buchstaben nach ISO 639-1, etwa de oder en; leer erkennt das Modell die Sprache selbst.',
  vocabularyLabel: 'Begriffsliste',
  vocabularyHint:
    'Fachbegriffe, Namen und Abkürzungen, kommagetrennt oder als Beispielsatz. Das Modell erkennt sie damit zuverlässiger.',
} as const

const LANGUAGE_CODE = /^[a-z]{2}$/

/** The rule's message for a language that is neither empty nor two letters, else `undefined`. */
export function transcriptionLanguageError(value: string): string | undefined {
  const language = value.trim().toLowerCase()
  return language === '' || LANGUAGE_CODE.test(language)
    ? undefined
    : TRANSCRIPTION_TEXTS.languageRule
}

/** The longest vocabulary list in characters; the backend applies the same cap. */
export const VOCABULARY_MAX_LENGTH = 500

/** Whether a list exceeds the cap; a form does not send it then. */
export function isVocabularyTooLong(value: string): boolean {
  return value.trim().length > VOCABULARY_MAX_LENGTH
}
