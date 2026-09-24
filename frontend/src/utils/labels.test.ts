import { describe, expect, it } from 'vitest'
import { capabilityMissingMessage } from './labels'
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
