import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Route, Routes } from 'react-router'
import ExtensionIcon from '@mui/icons-material/Extension'
import { renderWithProviders, setMockAuthState } from '../test/test-utils'
import { server } from '../mocks/server'
import type { AssetType, CatalogEntryResponse } from '../types/api'
import { useCatalogStore } from '../stores/catalogStore'
import { ASSET_TYPES, type AssetTypeDefinition } from '../components/assets/assetTypeRegistry'
import CatalogPage from './CatalogPage'
import CatalogNewPage from './CatalogNewPage'
import GlobalRail from '../layouts/GlobalRail'
import { isGlobalAreaPath } from '../layouts/globalArea'

/** A type the application does not know - it exists only as this registry entry. */
const SKILL = 'SKILL' as AssetType

const skillType: AssetTypeDefinition = {
  type: SKILL,
  slug: 'skills',
  label: 'Skills',
  title: 'Skill',
  noun: 'Skill',
  description: 'Wiederverwendbare Arbeitsschritte für den Chat.',
  Icon: ExtensionIcon,
  routePrefix: '/skills',
  detailRoute: (assetId) => `/skills/${assetId}`,
  createRoute: '/skills/new',
  createCapabilities: ['CREATE_PROMPT_LIBRARY'],
  extentLabel: (count) => `${count} Schritte`,
}

const skillEntry = {
  assetType: SKILL,
  assetId: 'skill-1',
  name: 'Aktenvermerk schreiben',
  description: null,
  ownerType: 'USER',
  ownerLabel: 'Dana Beispiel',
  origin: 'LOCAL',
  itemCount: 4,
  spaceCount: 0,
  succession: null,
} as unknown as CatalogEntryResponse

// The acceptance criterion of #2094: a new asset type needs nothing but a registry entry to appear
// in the catalog, in its type filter and under "Neu".
describe('a new asset type through the registry alone', () => {
  beforeEach(() => {
    setMockAuthState()
    useCatalogStore.getState().reset()
    ASSET_TYPES.push(skillType)
    server.use(
      http.get('/api/v1/catalog', ({ request }) => {
        const type = new URL(request.url).searchParams.get('type')
        const entries = !type || type === SKILL ? [skillEntry] : []
        return HttpResponse.json({
          entries,
          page: 0,
          size: 50,
          totalElements: entries.length,
          totalPages: 1,
        })
      }),
    )
  })

  afterEach(() => {
    ASSET_TYPES.splice(ASSET_TYPES.indexOf(skillType), 1)
  })

  it('appears as a card with its badge and route, and as a type filter', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CatalogPage />, { withRouter: true, initialRoute: '/catalog' })

    const card = await screen.findByRole('link', { name: /Aktenvermerk schreiben/ })
    expect(card).toHaveAttribute('href', '/skills/skill-1')
    expect(within(card).getByText('Skill')).toBeInTheDocument()
    expect(within(card).getByText('4 Schritte · in keinem Space')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Wissen' }))
    await waitFor(() =>
      expect(
        screen.queryByRole('link', { name: /Aktenvermerk schreiben/ }),
      ).not.toBeInTheDocument(),
    )
    await user.click(screen.getByRole('button', { name: 'Skills' }))
    expect(await screen.findByRole('link', { name: /Aktenvermerk schreiben/ })).toBeInTheDocument()
  })

  it('is offered under "Neu" and leads to its own wizard', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Routes>
        <Route path="/catalog/new" element={<CatalogNewPage />} />
        <Route path="/skills/new" element={<div>Assistent Skills</div>} />
      </Routes>,
      { withRouter: true, initialRoute: '/catalog/new' },
    )

    const tile = await screen.findByRole('radio', { name: /Skills/ })
    expect(tile).toHaveTextContent('Wiederverwendbare Arbeitsschritte für den Chat.')
    await user.click(tile)
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    expect(await screen.findByText('Assistent Skills')).toBeInTheDocument()
  })

  it('renders its pages in the global frame under the rail entry "Katalog"', () => {
    expect(isGlobalAreaPath('/skills/skill-1')).toBe(true)
    expect(isGlobalAreaPath('/skills/new')).toBe(true)

    renderWithProviders(<GlobalRail />, { withRouter: true, initialRoute: '/skills/skill-1' })

    expect(screen.getByRole('link', { name: 'Katalog' })).toHaveAttribute('aria-current', 'true')
  })
})
