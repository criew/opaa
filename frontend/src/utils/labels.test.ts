import { describe, expect, it } from 'vitest'
import { assetRoleDescription, capabilityMissingMessage, documentCountLabel } from './labels'
import type { Capability } from '../types/api'

/**
 * The refusal the creation dialogs show before the attempt has to read word for word like the one
 * the backend answers with after it - `CapabilityService#requireCapability` holds that copy. The
 * expectations here are therefore spelled out rather than derived from the function under test: a
 * test that called it on both sides would agree with any wording it happens to produce.
 */
describe('capabilityMissingMessage', () => {
  it('names the right by its German label and says who to ask', () => {
    expect(capabilityMissingMessage('CREATE_SPACE')).toBe(
      'Ihnen fehlt das Anlegerecht „Spaces anlegen“. Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.',
    )
    expect(capabilityMissingMessage('CREATE_LIBRARY')).toBe(
      'Ihnen fehlt das Anlegerecht „Bibliotheken für Uploads anlegen“. Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.',
    )
    expect(capabilityMissingMessage('CREATE_CONNECTOR_LIBRARY')).toBe(
      'Ihnen fehlt das Anlegerecht „Konnektorbibliotheken anlegen“. Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.',
    )
    expect(capabilityMissingMessage('CREATE_INTERNAL_GROUP')).toBe(
      'Ihnen fehlt das Anlegerecht „Interne Gruppen anlegen“. Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.',
    )
    expect(capabilityMissingMessage('CREATE_PROMPT_LIBRARY')).toBe(
      'Ihnen fehlt das Anlegerecht „Prompt-Bibliotheken anlegen“. Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.',
    )
  })

  it('has a sentence for every capability the API knows', () => {
    const everyCapability: Capability[] = [
      'CREATE_SPACE',
      'CREATE_LIBRARY',
      'CREATE_CONNECTOR_LIBRARY',
      'CREATE_INTERNAL_GROUP',
      'CREATE_PROMPT_LIBRARY',
    ]

    for (const capability of everyCapability) {
      expect(capabilityMissingMessage(capability)).not.toContain('undefined')
    }
  })
})

describe('documentCountLabel (#1916)', () => {
  it('writes small counts in full and shortens from five digits on', () => {
    expect(documentCountLabel(0)).toBe('0')
    expect(documentCountLabel(431)).toBe('431')
    expect(documentCountLabel(9_999)).toBe('9.999')
    expect(documentCountLabel(120_000)).toBe('120 K')
    // Das Runden trägt nie über die nächste Größenordnung hinweg - sonst stünde hier „1.000 K".
    expect(documentCountLabel(999_999)).toBe('1 Mio.')
    expect(documentCountLabel(1_234_567)).toBe('1,2 Mio.')
  })
})

describe('assetRoleDescription', () => {
  it('describes a role in the words of the asset type', () => {
    expect(assetRoleDescription('EDITOR', 'KNOWLEDGE_LIBRARY')).toBe(
      'Darf zusätzlich Dokumente ändern, hochladen und entfernen.',
    )
    expect(assetRoleDescription('EDITOR', 'PROMPT_LIBRARY')).toBe(
      'Darf zusätzlich Prompts anlegen, ändern und löschen.',
    )
  })
})
