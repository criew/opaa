import { describe, expect, it } from 'vitest'
import { isRecognizedText } from './recognizedText'

describe('isRecognizedText', () => {
  it('recognizes an anchor from text recognition, typed or as a stored metadatum', () => {
    expect(isRecognizedText({ textOrigin: { connectionType: 'DOCLING_SERVE' } })).toBe(true)
    expect(isRecognizedText({ pageFrom: 1, textOrigin: { connectionType: 'OPENAI_VISION' } })).toBe(
      true,
    )
  })

  it('leaves text from the document itself and anything else unmarked', () => {
    expect(isRecognizedText({ pageFrom: 1, quoteStart: 'Satz' })).toBe(false)
    expect(isRecognizedText({ textOrigin: null })).toBe(false)
    expect(isRecognizedText(null)).toBe(false)
    expect(isRecognizedText(undefined)).toBe(false)
    expect(isRecognizedText('anchor')).toBe(false)
  })
})
