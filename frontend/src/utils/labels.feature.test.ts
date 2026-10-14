import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { useAuthStore } from '../shared/session/authStore'
import type { CapabilityOverviewResponse, SuccessionEntryResponse } from '../shared/api/types'
import { presentationOf } from '../components/admin/capabilities/capabilityPresentation'
import { objectPhrase } from '../components/admin/capabilities/capabilityAccess'
import { problemOf } from '../components/succession/successionPresentation'
import { assetGrantScopeHint, assetRoleDescription } from './labels'

/**
 * Texts naming prompt libraries only while the feature is on (#2588, ADR-0044). The backend leaves
 * the switched-off data out already; these texts must not name it should a stale entry slip in.
 */
describe('texts naming prompt libraries', () => {
  const capabilityEntry: CapabilityOverviewResponse = {
    capability: 'CREATE_PROMPT_LIBRARY',
    label: 'Anlegerecht',
    statement: 'Stand',
    grants: [],
  }
  const successionEntry: SuccessionEntryResponse = {
    caseId: 'case-1',
    objectType: 'ASSET',
    assetType: 'PROMPT_LIBRARY',
    objectId: 'prompt-1',
    objectName: 'Vorlagen',
    addressee: 'SYSTEM_ADMINISTRATION',
    addresseeLabel: 'die Systemverwaltung',
    ownerHint: null,
    membershipHints: [],
    affectedObjects: 0,
    firstSeenAt: null,
    highlighted: false,
  }

  afterEach(() => {
    useAuthStore.setState({ features: {} })
  })

  describe('while prompt libraries are on', () => {
    it('name them', () => {
      expect(assetRoleDescription('VIEWER', 'PROMPT_LIBRARY')).toContain('Prompt-Bibliothek')
      expect(assetGrantScopeHint('PROMPT_LIBRARY')).toContain('Prompt-Bibliothek')
      expect(presentationOf('CREATE_PROMPT_LIBRARY', 'Anlegerecht').title).toBe(
        'Prompt-Bibliotheken',
      )
      expect(objectPhrase(capabilityEntry)).toBe('Prompt-Bibliotheken anlegen')
      expect(problemOf('OPEN_SUCCESSION', successionEntry)).toContain('die Prompt-Bibliothek')
    })
  })

  describe('while prompt libraries are switched off', () => {
    beforeEach(() => {
      useAuthStore.setState({ features: { promptLibraries: false } })
    })

    it('name them nowhere', () => {
      expect(assetRoleDescription('VIEWER', 'PROMPT_LIBRARY')).toBe('')
      expect(assetGrantScopeHint('PROMPT_LIBRARY')).toBe('')
      expect(presentationOf('CREATE_PROMPT_LIBRARY', 'Anlegerecht').title).toBe('Anlegerecht')
      expect(presentationOf('CREATE_PROMPT_LIBRARY', 'Anlegerecht').description).toBe('')
      expect(objectPhrase(capabilityEntry)).toBe('Anlegerecht')
      expect(problemOf('OPEN_SUCCESSION', successionEntry)).not.toMatch(/Prompt/)
    })

    it('leave the knowledge library as it is', () => {
      expect(assetRoleDescription('VIEWER', 'KNOWLEDGE_LIBRARY')).toBe(
        'Darf die Bibliothek benutzen und ihren Inhalt einsehen.',
      )
    })
  })
})
