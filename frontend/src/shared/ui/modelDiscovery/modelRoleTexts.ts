/** The optional model roles beside chat and embedding, each with an endpoint of its own. */
export type ModelRoleKind = 'rerank' | 'transcription'

interface ModelRoleTexts {
  /** What the role does, in one sentence. */
  purpose: string
  /** How the base address is written: without the role's own path. */
  addressHint: string
}

/** Shared by the model administration and the set-up assistant, so both say the same. */
export const MODEL_ROLE_TEXTS: Record<ModelRoleKind, ModelRoleTexts> = {
  rerank: {
    purpose: 'Das Rerank-Modell ordnet die Kandidaten jeder Suche neu, bevor die Antwort entsteht.',
    addressHint: 'Ohne den Pfad /rerank, etwa http://reranker:80.',
  },
  transcription: {
    purpose: 'Das Transkriptionsmodell wandelt diktierte Fragen im Chat in Text.',
    addressHint: 'Ohne den Pfad /audio/transcriptions, etwa http://speaches:8000/v1.',
  },
}
