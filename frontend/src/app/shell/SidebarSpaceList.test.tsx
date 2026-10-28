import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '@/test/test-utils'
import { useUiStore } from '@/shared/stores/uiStore'
import type { SpaceListResponse } from '@/shared/api/types'
import SidebarSpaceList from './SidebarSpaceList'
import { Route, Routes, useLocation } from 'react-router'
import { SPACE_SEARCH_HANDOVER } from '@/features/space'

function LocationProbe() {
  const { pathname, state } = useLocation()
  return <output data-testid="location">{JSON.stringify({ pathname, state })}</output>
}

function space(
  id: string,
  name: string,
  extra: Partial<SpaceListResponse> = {},
): SpaceListResponse {
  return {
    id,
    name,
    description: null,
    isDefault: false,
    archived: false,
    memberCount: 1,
    memberships: { groupCount: 0, userCount: 1 },
    userRole: 'ADMIN',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
    ...extra,
  }
}

/** Nine spaces in list order: the personal one first, then Zeta down to Ruby. */
const SPACES = [
  space('space-personal', 'Privater Bereich', { isDefault: true }),
  space('space-zeta', 'Zeta'),
  space('space-ypsilon', 'Ypsilon'),
  space('space-xenon', 'Xenon'),
  space('space-wega', 'Wega'),
  space('space-vega', 'Vega'),
  space('space-uran', 'Uran'),
  space('space-tango', 'Tango'),
  space('space-old', 'Altakte', { archived: true }),
]

function renderList(activeSpaceId: string | null, spaces = SPACES) {
  return renderWithProviders(
    <SidebarSpaceList spaces={spaces} activeSpaceId={activeSpaceId} isLoading={false} />,
    { withRouter: true },
  )
}

function rowNames(): string[] {
  return within(screen.getByRole('list', { name: /^Spaces/ }))
    .getAllByRole('link')
    .map((link) => link.querySelector('.MuiListItemText-primary')?.textContent ?? '')
}

describe('SidebarSpaceList', () => {
  beforeEach(() => window.localStorage.clear())
  afterEach(() => useUiStore.setState({ collapsedSidebarSections: [] }))

  it('shows up to five spaces picked by recent use, ordered personal space first, then by name', () => {
    window.localStorage.setItem(
      'opaa.spaces.recent',
      JSON.stringify(['space-uran', 'space-tango', 'space-vega']),
    )
    renderList('space-uran')

    // Three recently used, filled up in list order with the personal space and Zeta.
    expect(rowNames()).toEqual(['Privater Bereich', 'Tango', 'Uran', 'Vega', 'Zeta'])
  })

  it('always includes the active space, even when it is not among the recent ones', () => {
    renderList('space-tango')

    const names = rowNames()
    expect(names).toHaveLength(5)
    expect(names).toContain('Tango')
    expect(screen.getByRole('link', { name: 'Tango' })).toHaveAttribute('aria-current', 'true')
    expect(screen.getByRole('link', { name: 'Zeta' })).not.toHaveAttribute('aria-current')
  })

  it('leads a row into a new chat of its space, an archived one to its overview', () => {
    window.localStorage.setItem('opaa.spaces.recent', JSON.stringify(['space-old']))
    renderList('space-personal')

    expect(screen.getByRole('link', { name: 'Zeta' })).toHaveAttribute(
      'href',
      '/spaces/space-zeta/chats/new',
    )
    // An archived space only appears when it was used recently, with its hint.
    const archived = screen.getByRole('link', { name: /Altakte/ })
    expect(archived).toHaveAttribute('href', '/spaces/space-old')
    expect(archived).toHaveTextContent('archiviert')
  })

  it('shows an archived space only when it was used recently', () => {
    renderList('space-personal')

    expect(screen.queryByRole('link', { name: /Altakte/ })).not.toBeInTheDocument()
  })

  it('counts the person’s spaces that are not archived in the head', () => {
    renderList('space-personal')

    expect(screen.getByRole('heading', { level: 2, name: 'Spaces 8' })).toBeInTheDocument()
  })

  it('offers plus and search as icon buttons with name and tooltip', async () => {
    const user = userEvent.setup()
    renderList('space-personal')

    const create = screen.getByRole('link', { name: 'Neuer Space' })
    expect(create).toHaveAttribute('href', '/spaces/new')
    expect(create).not.toHaveTextContent(/Neu/)
    expect(
      screen.getByRole('link', { name: 'Alle Spaces anzeigen und durchsuchen' }),
    ).toHaveAttribute('href', '/spaces')

    await user.hover(create)
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Neuer Space')
  })

  it('opens the space search with the cursor handover, like the chat search', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Routes>
        <Route
          path="*"
          element={
            <>
              <SidebarSpaceList spaces={SPACES} activeSpaceId="space-personal" isLoading={false} />
              <LocationProbe />
            </>
          }
        />
      </Routes>,
      { withRouter: true },
    )

    await user.click(screen.getByRole('link', { name: 'Alle Spaces anzeigen und durchsuchen' }))

    expect(screen.getByTestId('location')).toHaveTextContent(
      JSON.stringify({ pathname: '/spaces', state: SPACE_SEARCH_HANDOVER }),
    )
  })

  it('keeps the row of the active space visible while the spaces are folded', async () => {
    const user = userEvent.setup()
    renderList('space-tango')

    const toggle = screen.getByRole('button', { name: 'Spaces 8' })
    await user.click(toggle)

    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(rowNames()).toEqual(['Tango'])
    expect(useUiStore.getState().collapsedSidebarSections).toContain('spaces')
  })
})
