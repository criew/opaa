/**
 * Texts and the language rule of the transcription model, shared by the model administration
 * („Modelle → Spracheingabe“) and the installation step; the backend applies the same rule
 * (`TranscriptionEndpoint`).
 */
export const TRANSCRIPTION_TEXTS = {
  intro:
    'Das Transkriptionsmodell wandelt diktierte Fragen im Chat in Text. Ab Werk ist es aus; das Mikrofon erscheint erst, wenn das Modell antwortet.',
  addressHint: 'Ohne den Pfad /audio/transcriptions, etwa http://speaches:8000/v1.',
  languageLabel: 'Sprache',
  languageHint: 'Kürzel aus zwei Buchstaben, etwa de; leer erkennt das Modell die Sprache selbst.',
  languageRule:
    'Die Sprache ist ein Kürzel aus zwei Buchstaben nach ISO 639-1, etwa de oder en; leer erkennt das Modell die Sprache selbst.',
} as const

const LANGUAGE_CODE = /^[a-z]{2}$/

/** The rule's message for a language that is neither empty nor two letters, else `undefined`. */
export function transcriptionLanguageError(value: string): string | undefined {
  const language = value.trim().toLowerCase()
  return language === '' || LANGUAGE_CODE.test(language)
    ? undefined
    : TRANSCRIPTION_TEXTS.languageRule
}
