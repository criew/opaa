import { describe, expect, it } from 'vitest'
import { TRANSCRIPTION_TEXTS, transcriptionLanguageError } from './transcriptionSettings'

describe('transcriptionSettings', () => {
  it('takes two letters or nothing as language, as the backend does', () => {
    expect(transcriptionLanguageError('')).toBeUndefined()
    expect(transcriptionLanguageError(' DE ')).toBeUndefined()
    expect(transcriptionLanguageError('deu')).toBe(TRANSCRIPTION_TEXTS.languageRule)
    expect(transcriptionLanguageError('d1')).toBe(TRANSCRIPTION_TEXTS.languageRule)
  })
})
