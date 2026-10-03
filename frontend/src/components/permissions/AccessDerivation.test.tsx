import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import type { AccessPathResponse } from '../../types/api'
import AccessDerivation from './AccessDerivation'

const { mockGetAssetAccessDerivation, mockGetSpaceAccessDerivation } = vi.hoisted(() => ({
  mockGetAssetAccessDerivation: vi.fn(),
  mockGetSpaceAccessDerivation: vi.fn(),
}))

vi.mock('../../services/assetApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../services/assetApi')>('../../services/assetApi')
  return {
    ...actual,
    getAssetAccessDerivation: mockGetAssetAccessDerivation,
  }
})

vi.mock('../../services/spaceApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../services/spaceApi')>('../../services/spaceApi')
  return {
    ...actual,
    getSpaceAccessDerivation: mockGetSpaceAccessDerivation,
  }
})

const meldewesen = {
  id: 'group-meldewesen',
  name: 'Meldewesen',
  origin: 'PROVIDER' as const,
  mechanism: 'DIRECTORY' as const,
  providerName: 'Verzeichnis Haus A',
}

function spacePath(
  basis: AccessPathResponse['basis'],
  spaceRole: AccessPathResponse['spaceRole'],
  group: AccessPathResponse['group'] = null,
): AccessPathResponse {
  return { basis, spaceRole, assetRole: null, since: '2026-03-01T10:00:00Z', group }
}

function assetPath(
  basis: AccessPathResponse['basis'],
  assetRole: AccessPathResponse['assetRole'],
  group: AccessPathResponse['group'] = null,
): AccessPathResponse {
  return { basis, assetRole, spaceRole: null, since: '2026-03-01T10:00:00Z', group }
}

function answerSpace(
  effectiveRole: 'MEMBER' | 'CURATOR' | 'ADMIN' | null,
  paths: AccessPathResponse[],
  pathsWithheld = false,
) {
  mockGetSpaceAccessDerivation.mockResolvedValue({
    spaceId: 'space-1',
    userId: 'user-2',
    effectiveRole,
    pathsWithheld,
    paths,
  })
}

function answerAsset(
  effectiveRole: 'VIEWER' | 'EDITOR' | 'MANAGER' | 'OWNER',
  paths: AccessPathResponse[],
  pathsWithheld = false,
) {
  mockGetAssetAccessDerivation.mockResolvedValue({
    assetType: 'KNOWLEDGE_LIBRARY',
    assetId: 'library-1',
    effectiveRole,
    pathsWithheld,
    paths,
  })
}

function renderSpace(subjectName: string | null = 'Thomas Klein') {
  return renderWithProviders(
    <AccessDerivation
      target={{ kind: 'space', spaceId: 'space-1', userId: 'user-2' }}
      subjectName={subjectName}
    />,
  )
}

function renderAsset(subjectName: string | null = 'Maria Weber') {
  return renderWithProviders(
    <AccessDerivation
      target={{ kind: 'asset', assetType: 'KNOWLEDGE_LIBRARY', assetId: 'library-1' }}
      subjectName={subjectName}
    />,
  )
}

/** What was dropped from the derivation (#2205) - none of it may come back. */
function expectNoDroppedDetails() {
  const text = document.body.textContent ?? ''
  expect(text).not.toMatch(/Verzeichnis|gepflegt über|seit \d/)
  expect(text).not.toMatch(/nicht offengelegt/)
  expect(text).not.toMatch(/Wirksame Rolle/)
  expect(document.querySelector('ul, li')).toBeNull()
  expect(text).not.toContain('·')
}

describe('AccessDerivation (#1822, ADR-0036 Entscheidung 9)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Space (#2205)', () => {
    it('names the only way in the first sentence', async () => {
      answerSpace('CURATOR', [spacePath('DIRECT_MEMBERSHIP', 'CURATOR')])
      renderSpace()

      expect(
        await screen.findByText('Thomas Klein ist Kurator in diesem Space – direkt aufgenommen.'),
      ).toBeInTheDocument()
      expect(screen.queryByText('Es gilt die höhere Rolle.')).not.toBeInTheDocument()
      expectNoDroppedDetails()
      expect(mockGetSpaceAccessDerivation).toHaveBeenCalledWith('space-1', 'user-2')
    })

    it('names a group as the only way without its origin or a date', async () => {
      answerSpace('MEMBER', [spacePath('GROUP_MEMBERSHIP', 'MEMBER', meldewesen)])
      renderSpace()

      const sentence = await screen.findByText(
        'Thomas Klein ist Mitglied in diesem Space – über die Gruppe Meldewesen.',
      )
      expect(sentence).toHaveAttribute('title', 'Seit 1.3.2026')
      expectNoDroppedDetails()
    })

    it('gives every way a line of its own and states that the higher role applies', async () => {
      answerSpace('ADMIN', [
        spacePath('DIRECT_MEMBERSHIP', 'CURATOR'),
        spacePath('GROUP_MEMBERSHIP', 'ADMIN', meldewesen),
      ])
      renderSpace('Maria Weber')

      expect(
        await screen.findByText('Maria Weber ist Administrator in diesem Space.'),
      ).toBeInTheDocument()
      expect(screen.getByText('Direkt aufgenommen: Kurator')).toBeInTheDocument()
      expect(screen.getByText('Über die Gruppe Meldewesen: Administrator')).toBeInTheDocument()
      expect(screen.getByText('Es gilt die höhere Rolle.')).toBeInTheDocument()
      expectNoDroppedDetails()
    })

    it('keeps a protected group nameless and counts it as a way', async () => {
      answerSpace('ADMIN', [spacePath('DIRECT_MEMBERSHIP', 'CURATOR')], true)
      renderSpace()

      expect(
        await screen.findByText('Thomas Klein ist Administrator in diesem Space.'),
      ).toBeInTheDocument()
      expect(screen.getByText('Direkt aufgenommen: Kurator')).toBeInTheDocument()
      expect(
        screen.getByText('Ein Weg führt über eine geschützte Gruppe und wird hier nicht benannt.'),
      ).toBeInTheDocument()
      expect(screen.getByText('Es gilt die höhere Rolle.')).toBeInTheDocument()
    })

    it('states only the role when the single way runs through a protected group', async () => {
      answerSpace('MEMBER', [], true)
      renderSpace()

      expect(
        await screen.findByText('Thomas Klein ist Mitglied in diesem Space.'),
      ).toBeInTheDocument()
      expect(
        screen.getByText('Ein Weg führt über eine geschützte Gruppe und wird hier nicht benannt.'),
      ).toBeInTheDocument()
      expect(screen.queryByText('Es gilt die höhere Rolle.')).not.toBeInTheDocument()
    })

    it('names the administration without a membership role', async () => {
      answerSpace(null, [spacePath('SYSTEM_ADMINISTRATION', null)])
      renderSpace(null)

      expect(
        await screen.findByText('Sie haben Zugriff auf diesen Space – über die Systemverwaltung.'),
      ).toBeInTheDocument()
    })
  })

  describe('Asset (#2205)', () => {
    it('names the only way in the first sentence', async () => {
      answerAsset('VIEWER', [assetPath('GROUP_GRANT', 'VIEWER', meldewesen)])
      renderAsset()

      expect(
        await screen.findByText(
          'Maria Weber darf diese Bibliothek lesen – über die Gruppe Meldewesen.',
        ),
      ).toBeInTheDocument()
      expect(screen.queryByText('Es gilt die höhere Rolle.')).not.toBeInTheDocument()
      expectNoDroppedDetails()
    })

    it('gives every way a line of its own and states that the higher role applies', async () => {
      answerAsset('EDITOR', [
        assetPath('DIRECT_GRANT', 'EDITOR'),
        assetPath('GROUP_GRANT', 'VIEWER', meldewesen),
      ])
      renderAsset()

      expect(
        await screen.findByText('Maria Weber darf diese Bibliothek bearbeiten.'),
      ).toBeInTheDocument()
      expect(screen.getByText('Direkt freigegeben: Bearbeiter')).toBeInTheDocument()
      expect(screen.getByText('Über die Gruppe Meldewesen: Leser')).toBeInTheDocument()
      expect(screen.getByText('Es gilt die höhere Rolle.')).toBeInTheDocument()
      expectNoDroppedDetails()
    })

    it('names "Alle Konten" as a way of its own', async () => {
      answerAsset('VIEWER', [assetPath('ORGANIZATION_WIDE', 'VIEWER')])
      renderAsset()

      expect(
        await screen.findByText(
          'Maria Weber darf diese Bibliothek lesen – für alle Konten freigegeben.',
        ),
      ).toBeInTheDocument()
    })

    it('lists "Alle Konten" beside other ways', async () => {
      answerAsset('MANAGER', [
        assetPath('ORGANIZATION_WIDE', 'VIEWER'),
        assetPath('DIRECT_GRANT', 'MANAGER'),
      ])
      renderAsset()

      expect(
        await screen.findByText('Maria Weber darf diese Bibliothek verwalten.'),
      ).toBeInTheDocument()
      expect(screen.getByText('Für alle Konten freigegeben: Leser')).toBeInTheDocument()
      expect(screen.getByText('Direkt freigegeben: Verwalter')).toBeInTheDocument()
    })

    it('keeps a protected group nameless', async () => {
      answerAsset('VIEWER', [], true)
      renderAsset()

      expect(
        await screen.findByText('Maria Weber darf diese Bibliothek lesen.'),
      ).toBeInTheDocument()
      expect(
        screen.getByText('Ein Weg führt über eine geschützte Gruppe und wird hier nicht benannt.'),
      ).toBeInTheDocument()
    })

    // Verwalten ist nicht Lesen: the administration grants no content role, so it neither
    // makes the reader an owner nor takes part in "die höhere Rolle".
    it('names the administration alone as managing without a read right', async () => {
      answerAsset('OWNER', [assetPath('SYSTEM_ADMINISTRATION', 'OWNER')])
      renderAsset()

      expect(
        await screen.findByText(
          'Maria Weber verwaltet diese Bibliothek über die Systemverwaltung (ohne Leserecht am Inhalt).',
        ),
      ).toBeInTheDocument()
      expect(document.body).not.toHaveTextContent(/Eigentümer|Es gilt die höhere Rolle/)
    })

    it('derives the role from the other ways and names the administration apart', async () => {
      answerAsset('OWNER', [
        assetPath('DIRECT_GRANT', 'VIEWER'),
        assetPath('SYSTEM_ADMINISTRATION', 'OWNER'),
      ])
      renderAsset()

      expect(
        await screen.findByText('Maria Weber darf diese Bibliothek lesen – direkt freigegeben.'),
      ).toBeInTheDocument()
      expect(
        screen.getByText(
          'Maria Weber verwaltet diese Bibliothek zudem über die Systemverwaltung (ohne Leserecht am Inhalt).',
        ),
      ).toBeInTheDocument()
      expect(document.body).not.toHaveTextContent(/Eigentümer|Es gilt die höhere Rolle/)
    })

    it('applies the higher role among the other ways only', async () => {
      answerAsset('OWNER', [
        assetPath('DIRECT_GRANT', 'VIEWER'),
        assetPath('GROUP_GRANT', 'EDITOR', meldewesen),
        assetPath('SYSTEM_ADMINISTRATION', 'OWNER'),
      ])
      renderAsset()

      expect(
        await screen.findByText('Maria Weber darf diese Bibliothek bearbeiten.'),
      ).toBeInTheDocument()
      expect(screen.getByText('Es gilt die höhere Rolle.')).toBeInTheDocument()
      expect(screen.queryByText(/Über die Systemverwaltung:/)).not.toBeInTheDocument()
      expect(screen.getByText(/zudem über die Systemverwaltung/)).toBeInTheDocument()
    })

    it('addresses the reader without a name', async () => {
      answerAsset('OWNER', [assetPath('DIRECT_GRANT', 'OWNER')])
      renderAsset(null)

      expect(
        await screen.findByText('Sie sind Eigentümer dieser Bibliothek – direkt freigegeben.'),
      ).toBeInTheDocument()
    })
  })

  it('names a failed request instead of showing an empty derivation', async () => {
    mockGetAssetAccessDerivation.mockRejectedValue(new Error('Bibliothek nicht gefunden'))
    renderAsset()

    expect(await screen.findByText('Bibliothek nicht gefunden')).toBeInTheDocument()
  })
})
