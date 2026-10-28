import { afterEach, describe, expect, it } from 'vitest'
import { useAuthStore } from '@/shared/session/authStore'
import {
  activeAssetTypes,
  assetTypeDefinition,
  assetTypeLabel,
  catalogRoute,
  catalogScopePrefixes,
  creatableAssetTypes,
} from './assetTypes'

/** Prompt libraries behind their installation-wide feature flag (ADR-0044). */
describe('assetTypeRegistry with prompt libraries switched off', () => {
  afterEach(() => {
    useAuthStore.setState({ features: {} })
  })

  it('offers every type while no flag is switched off', () => {
    expect(activeAssetTypes().map((definition) => definition.type)).toEqual([
      'KNOWLEDGE_LIBRARY',
      'PROMPT_LIBRARY',
    ])
    expect(creatableAssetTypes(() => false).map((definition) => definition.type)).toEqual([
      'KNOWLEDGE_LIBRARY',
      'PROMPT_LIBRARY',
    ])
    expect(catalogScopePrefixes()).toContain('/prompts')
  })

  it('leaves the prompt library out of every list, "Neuer Katalogeintrag" and the rail scope', () => {
    useAuthStore.setState({ features: { promptLibraries: false } })

    expect(activeAssetTypes().map((definition) => definition.type)).toEqual(['KNOWLEDGE_LIBRARY'])
    expect(creatableAssetTypes(() => false).map((definition) => definition.type)).toEqual([
      'KNOWLEDGE_LIBRARY',
    ])
    expect(catalogScopePrefixes()).not.toContain('/prompts')
  })

  it('knows no definition, label or catalog filter for the switched-off type', () => {
    useAuthStore.setState({ features: { promptLibraries: false } })

    expect(assetTypeDefinition('PROMPT_LIBRARY')).toBeUndefined()
    expect(assetTypeLabel('PROMPT_LIBRARY')).not.toBe('Prompt-Bibliothek')
    expect(catalogRoute('PROMPT_LIBRARY')).toBe('/catalog')
    expect(assetTypeDefinition('KNOWLEDGE_LIBRARY')?.noun).toBe('Bibliothek')
  })
})
