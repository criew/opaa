import { AxiosError } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from '../../mocks/server'
import { renderWithProviders, setMockAuthState } from '../../test/test-utils'
import type { AssetType } from '../../types/api'
import AssetDistributionSection from './AssetDistributionSection'

const ASSETS: Array<{ assetType: AssetType; assetId: string; name: string; noun: string }> = [
  {
    assetType: 'KNOWLEDGE_LIBRARY',
    assetId: 'library-referat-50',
    name: 'Rechtsquellen Soziales',
    noun: 'Bibliothek',
  },
  {
    assetType: 'PROMPT_LIBRARY',
    assetId: 'prompt-library-referat-50',
    name: 'Formulierungshilfen Referat 50',
    noun: 'Prompt-Bibliothek',
  },
]

describe('AssetDistributionSection', () => {
  beforeEach(() => {
    setMockAuthState()
  })

  describe.each(ASSETS)('für $assetType', ({ assetType, assetId, name, noun }) => {
    it('saves distribution level and findability together through the caller', async () => {
      const onSave = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()
      renderWithProviders(
        <AssetDistributionSection
          assetType={assetType}
          assetId={assetId}
          assetName={name}
          visibility="PRIVATE"
          listed={false}
          onSave={onSave}
        />,
      )

      expect(screen.getByRole('button', { name: 'Freigabe speichern' })).toBeDisabled()
      await user.click(screen.getByRole('combobox', { name: 'Verteilungsstufe' }))
      await user.click(await screen.findByRole('option', { name: 'geteilt' }))
      await user.click(screen.getByLabelText('Im Katalog auffindbar'))
      await user.click(screen.getByRole('button', { name: 'Freigabe speichern' }))

      await waitFor(() => expect(onSave).toHaveBeenCalledWith('SHARED', true))
    })

    it('opens the one rights dialog for this asset type', async () => {
      const grantRequests: string[] = []
      server.events.removeAllListeners()
      server.events.on('request:start', ({ request }) => {
        if (request.url.includes('/grants')) grantRequests.push(new URL(request.url).pathname)
      })
      const user = userEvent.setup()
      renderWithProviders(
        <AssetDistributionSection
          assetType={assetType}
          assetId={assetId}
          assetName={name}
          visibility="SHARED"
          listed={false}
          onSave={vi.fn()}
        />,
      )

      await user.click(screen.getByRole('button', { name: 'Rechte verwalten' }))

      const dialog = await screen.findByRole('dialog')
      expect(within(dialog).getByText(new RegExp(`dieser ${noun},`))).toBeInTheDocument()
      await waitFor(() =>
        expect(grantRequests).toContain(`/api/v1/assets/${assetType}/${assetId}/grants`),
      )
      server.events.removeAllListeners()
    })

    it('lists the spaces the asset is associated with and derives the own access', async () => {
      server.use(
        http.get(`/api/v1/assets/${assetType}/${assetId}/spaces`, () =>
          HttpResponse.json({
            items: [
              {
                spaceId: 'space-phoenix',
                spaceName: 'Projekt Phoenix',
                createdByUserId: 'u1',
                createdAt: '2026-09-01T10:00:00Z',
                narrowerReaderCircle: true,
              },
            ],
            hiddenCount: 0,
          }),
        ),
      )
      const user = userEvent.setup()
      renderWithProviders(
        <AssetDistributionSection
          assetType={assetType}
          assetId={assetId}
          assetName={name}
          visibility="SHARED"
          listed={false}
          onSave={vi.fn()}
        />,
      )

      expect(await screen.findByText('Projekt Phoenix')).toBeInTheDocument()
      expect(screen.getByText('nicht alle Mitglieder lesen')).toBeInTheDocument()
      expect(
        screen.getByRole('heading', { name: `Warum sehe ich diese ${noun}?` }),
      ).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Herleitung anzeigen' }))
      expect(await screen.findByText(/Referat 50/)).toBeInTheDocument()
    })
  })

  it('disables levels above a cap and puts the type-specific control into its slot', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <AssetDistributionSection
        assetType="KNOWLEDGE_LIBRARY"
        assetId="library-referat-50"
        assetName="Rechtsquellen Soziales"
        visibility="PRIVATE"
        listed={false}
        onSave={vi.fn()}
        visibilityCap="SHARED"
        listedCap={false}
        capControl={<div>Obergrenze der Systemverwaltung</div>}
      />,
    )

    expect(screen.getByText('Obergrenze der Systemverwaltung')).toBeInTheDocument()
    expect(screen.getByLabelText('Im Katalog auffindbar')).toBeDisabled()
    await user.click(screen.getByRole('combobox', { name: 'Verteilungsstufe' }))
    expect(await screen.findByRole('option', { name: 'organisationsweit' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
  })

  it('names the takeover when an open succession refuses a wider reach', async () => {
    const refusal = new Error(
      'Für dieses Objekt ist die Nachfolge offen: eine größere Reichweite ist deshalb nicht möglich.',
    )
    // A real AxiosError as cause, as normalizeError attaches it: apiErrorCode reads the code there.
    const axiosError = new AxiosError('Request failed')
    axiosError.response = {
      status: 409,
      statusText: 'Conflict',
      headers: {},
      config: { headers: {} } as never,
      data: { error: refusal.message, code: 'SUCCESSION_OPEN' },
    }
    Object.defineProperty(refusal, 'cause', { value: axiosError })
    const onSave = vi.fn().mockRejectedValue(refusal)
    const user = userEvent.setup()
    renderWithProviders(
      <AssetDistributionSection
        assetType="PROMPT_LIBRARY"
        assetId="prompt-library-referat-50"
        assetName="Formulierungshilfen Referat 50"
        visibility="PRIVATE"
        listed={false}
        onSave={onSave}
      />,
    )

    await user.click(screen.getByLabelText('Im Katalog auffindbar'))
    await user.click(screen.getByRole('button', { name: 'Freigabe speichern' }))

    expect(await screen.findByText(/Nachfolge offen/)).toBeInTheDocument()
    expect(screen.getByText(/Übernahme/)).toBeInTheDocument()
  })

  it('shows any other refusal verbatim, without the takeover hint', async () => {
    const onSave = vi.fn().mockRejectedValue(new Error('Keine Berechtigung'))
    const user = userEvent.setup()
    renderWithProviders(
      <AssetDistributionSection
        assetType="KNOWLEDGE_LIBRARY"
        assetId="library-referat-50"
        assetName="Rechtsquellen Soziales"
        visibility="PRIVATE"
        listed={false}
        onSave={onSave}
      />,
    )

    await user.click(screen.getByLabelText('Im Katalog auffindbar'))
    await user.click(screen.getByRole('button', { name: 'Freigabe speichern' }))

    expect(await screen.findByText('Keine Berechtigung')).toBeInTheDocument()
    expect(screen.queryByText(/Übernahme/)).not.toBeInTheDocument()
  })
})
