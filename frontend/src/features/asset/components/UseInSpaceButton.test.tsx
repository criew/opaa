import { describe, expect, it, beforeEach, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '@/test/test-utils'
import { server } from '@/test/server'
import { mockSpaces } from '@/features/space/mocks/spaceFixtures'
import type { SpaceListResponse } from '@/shared/api/types'
import UseInSpaceButton from './UseInSpaceButton'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return { ...actual, useNavigate: () => mockNavigate }
})

function renderButton() {
  renderWithProviders(
    <UseInSpaceButton
      assetType="KNOWLEDGE_LIBRARY"
      assetId="library-dienstanweisungen"
      name="Dienstanweisungen"
    />,
    { withRouter: true },
  )
}

describe('UseInSpaceButton', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
  })

  it('associates with a curated space in two clicks', async () => {
    const requests: Array<{ spaceId: string; body: unknown }> = []
    server.use(
      http.post('/api/v1/spaces/:spaceId/assets', async ({ params, request }) => {
        requests.push({ spaceId: String(params.spaceId), body: await request.json() })
        return HttpResponse.json({}, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderButton()

    await user.click(screen.getByRole('button', { name: '„Dienstanweisungen“ in Space verwenden' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(await within(dialog).findByRole('button', { name: /^Phoenix/ }))

    await waitFor(() =>
      expect(requests).toEqual([
        {
          spaceId: 'space-phoenix',
          body: { assetType: 'KNOWLEDGE_LIBRARY', assetId: 'library-dienstanweisungen' },
        },
      ]),
    )
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('offers no second association to a space that already has the asset', async () => {
    const user = userEvent.setup()
    renderButton()

    await user.click(screen.getByRole('button', { name: '„Dienstanweisungen“ in Space verwenden' }))
    const engineering = await within(await screen.findByRole('dialog')).findByRole('button', {
      name: /^Engineering/,
    })

    expect(engineering).toHaveAttribute('aria-disabled', 'true')
    expect(engineering).toHaveTextContent('Bereits zugeordnet')
  })

  it('starts a new space with the asset already chosen', async () => {
    const user = userEvent.setup()
    renderButton()

    await user.click(screen.getByRole('button', { name: '„Dienstanweisungen“ in Space verwenden' }))
    await user.click(
      await within(await screen.findByRole('dialog')).findByRole('button', {
        name: 'Neuen Space damit anlegen',
      }),
    )

    expect(mockNavigate).toHaveBeenCalledWith('/spaces/new', {
      state: {
        preselect: {
          assetType: 'KNOWLEDGE_LIBRARY',
          assetId: 'library-dienstanweisungen',
          name: 'Dienstanweisungen',
        },
      },
    })
  })

  it('offers only spaces the person curates or administers, and no archived one', async () => {
    const space = (id: string, overrides: Partial<SpaceListResponse>): SpaceListResponse => ({
      ...mockSpaces[0],
      id,
      name: id,
      isDefault: false,
      ...overrides,
    })
    server.use(
      http.get('/api/v1/spaces', () =>
        HttpResponse.json([
          space('Als Mitglied', { userRole: 'MEMBER' }),
          space('Als Kurator', { userRole: 'CURATOR' }),
          space('Als Administrator', { userRole: 'ADMIN' }),
          space('Archiviert', { userRole: 'ADMIN', archived: true }),
        ]),
      ),
    )
    const user = userEvent.setup()
    renderButton()

    await user.click(screen.getByRole('button', { name: '„Dienstanweisungen“ in Space verwenden' }))
    const list = await within(await screen.findByRole('dialog')).findByRole('list', {
      name: 'Spaces, die Sie kuratieren',
    })

    expect(await within(list).findByRole('button', { name: 'Als Kurator' })).toBeInTheDocument()
    expect(within(list).getByRole('button', { name: 'Als Administrator' })).toBeInTheDocument()
    expect(within(list).queryByRole('button', { name: 'Als Mitglied' })).not.toBeInTheDocument()
    expect(within(list).queryByRole('button', { name: 'Archiviert' })).not.toBeInTheDocument()
    // A list holds list items only (axe "list"): every entry is an <li> around its button.
    expect(Array.from(list.children).map((child) => child.tagName)).toEqual(['LI', 'LI', 'LI'])
  })

  // An open succession refuses every new association (ADR-0036/6): nothing on offer may succeed.
  it('locks every way to a new association while the succession is open, naming why', async () => {
    const requests: string[] = []
    server.use(
      http.post('/api/v1/spaces/:spaceId/assets', ({ params }) => {
        requests.push(String(params.spaceId))
        return HttpResponse.json({}, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(
      <UseInSpaceButton
        assetType="KNOWLEDGE_LIBRARY"
        assetId="library-dienstanweisungen"
        name="Dienstanweisungen"
        reachFrozen
      />,
      { withRouter: true },
    )

    await user.click(screen.getByRole('button', { name: '„Dienstanweisungen“ in Space verwenden' }))
    const dialog = await screen.findByRole('dialog')
    const phoenix = await within(dialog).findByRole('button', { name: /^Phoenix/ })
    const newSpace = within(dialog).getByRole('button', { name: 'Neuen Space damit anlegen' })
    const reason = /Solange die Nachfolge offen ist, lässt sich „Dienstanweisungen“ nicht neu zuordnen/

    expect(within(dialog).getByText(reason)).toBeVisible()
    for (const entry of [phoenix, newSpace]) {
      expect(entry).toHaveAttribute('aria-disabled', 'true')
      expect(entry).toHaveAccessibleDescription(reason)
    }

    await user.click(phoenix)
    await user.click(newSpace)
    phoenix.focus()
    expect(phoenix).toHaveFocus()
    await user.keyboard('{Enter}')

    expect(requests).toEqual([])
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('offers the spaces of the space list the application frame provides', async () => {
    const loadSpaces = vi.fn(async (): Promise<SpaceListResponse[]> => [
      { ...mockSpaces[0], id: 'space-provided', name: 'Bereitgestellt', userRole: 'CURATOR' },
    ])
    const user = userEvent.setup()
    renderWithProviders(
      <UseInSpaceButton
        assetType="KNOWLEDGE_LIBRARY"
        assetId="library-dienstanweisungen"
        name="Dienstanweisungen"
      />,
      { withRouter: true, spaceList: loadSpaces },
    )

    await user.click(screen.getByRole('button', { name: '„Dienstanweisungen“ in Space verwenden' }))
    const list = await within(await screen.findByRole('dialog')).findByRole('list', {
      name: 'Spaces, die Sie kuratieren',
    })

    expect(await within(list).findByRole('button', { name: 'Bereitgestellt' })).toBeInTheDocument()
    expect(within(list).queryByRole('button', { name: /^Phoenix/ })).not.toBeInTheDocument()
    expect(loadSpaces).toHaveBeenCalledTimes(1)
  })
})
