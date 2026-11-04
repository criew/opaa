import { describe, expect, it, beforeEach, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '@/test/test-utils'
import { server } from '@/test/server'
import { mockSpaces } from '@/features/space/mocks/spaceFixtures'
import type { SpaceListResponse } from '@/shared/api/types'
import { UseInSpaceInline } from './UseInSpaceButton'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return { ...actual, useNavigate: () => mockNavigate }
})

const onDone = vi.fn()

/** The inline form behind „In Space verwenden“, as the Reiter „Zuordnungen“ opens it. */
function renderInline(
  props: { reachFrozen?: boolean } = {},
  spaceList?: () => Promise<SpaceListResponse[]>,
) {
  return renderWithProviders(
    <UseInSpaceInline
      assetType="KNOWLEDGE_LIBRARY"
      assetId="library-dienstanweisungen"
      name="Dienstanweisungen"
      reachFrozen={props.reachFrozen}
      onDone={onDone}
    />,
    { withRouter: true, ...(spaceList ? { spaceList } : {}) },
  )
}

describe('UseInSpaceInline', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    onDone.mockReset()
  })

  it('associates with a searched space on „Verwenden"', async () => {
    const requests: Array<{ spaceId: string; body: unknown }> = []
    server.use(
      http.post('/api/v1/spaces/:spaceId/assets', async ({ params, request }) => {
        requests.push({ spaceId: String(params.spaceId), body: await request.json() })
        return HttpResponse.json({}, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderInline()

    const field = await screen.findByRole('combobox', { name: 'Space suchen' })
    await user.type(field, 'Phoe')
    await user.click(await screen.findByRole('option', { name: /^Phoenix/ }))
    await user.click(screen.getByRole('button', { name: 'Verwenden' }))

    await waitFor(() =>
      expect(requests).toEqual([
        {
          spaceId: 'space-phoenix',
          body: { assetType: 'KNOWLEDGE_LIBRARY', assetId: 'library-dienstanweisungen' },
        },
      ]),
    )
    expect(onDone).toHaveBeenCalledTimes(1)
  })

  it('offers only curated or administered, non-archived spaces, and no already associated one', async () => {
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
      // the asset already stands in „Als Administrator"
      http.get('/api/v1/assets/:assetType/:assetId/spaces', () =>
        HttpResponse.json({
          items: [{ spaceId: 'Als Administrator', spaceName: 'Als Administrator' }],
          hiddenCount: 0,
        }),
      ),
    )
    const user = userEvent.setup()
    renderInline()

    const field = await screen.findByRole('combobox', { name: 'Space suchen' })
    await user.click(field)

    const options = (await screen.findAllByRole('option')).map((option) => option.textContent)
    expect(options).toEqual(['Als Kurator'])
  })

  it('starts a new space with the asset already chosen', async () => {
    const user = userEvent.setup()
    renderInline()

    await user.click(await screen.findByRole('button', { name: 'Neuen Space anlegen' }))

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

  // An open succession refuses every new association (ADR-0036/6): no field, no link, only the why.
  it('offers no way to a new association while the succession is open, naming why', async () => {
    const user = userEvent.setup()
    renderInline({ reachFrozen: true })

    expect(
      await screen.findByText(
        /Solange die Nachfolge offen ist, lässt sich „Dienstanweisungen“ nicht neu zuordnen/,
      ),
    ).toBeVisible()
    expect(screen.queryByRole('combobox', { name: 'Space suchen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Verwenden' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Neuen Space anlegen' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Abbrechen' }))
    expect(onDone).toHaveBeenCalledTimes(1)
  })

  it('offers the spaces of the space list the application frame provides', async () => {
    const loadSpaces = vi.fn(async (): Promise<SpaceListResponse[]> => [
      { ...mockSpaces[0], id: 'space-provided', name: 'Bereitgestellt', userRole: 'CURATOR' },
    ])
    const user = userEvent.setup()
    renderInline({}, loadSpaces)

    const field = await screen.findByRole('combobox', { name: 'Space suchen' })
    await user.click(field)

    const options = (await screen.findAllByRole('option')).map((option) => option.textContent)
    expect(options).toEqual(['Bereitgestellt'])
    expect(loadSpaces).toHaveBeenCalledTimes(1)
  })
})
