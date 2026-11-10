import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, onTestFinished, vi } from 'vitest'
import { server } from '@/test/server'
import { renderWithProviders } from '@/test/test-utils'
import { mockCompletedToolCheck, mockLlmModels } from '../mocks/modelFixtures'
import ToolCheckDialog from './ToolCheckDialog'

const model = mockLlmModels[0]
const LATEST = `/api/v1/admin/models/${model.id}/tool-checks/latest`

function renderDialog(onAdopt = vi.fn()) {
  renderWithProviders(<ToolCheckDialog model={model} open onClose={vi.fn()} onAdopt={onAdopt} />)
  return { onAdopt, dialog: () => screen.getByRole('dialog', { name: /Werkzeuge prüfen/ }) }
}

describe('ToolCheckDialog', () => {
  it('starts a check with the chosen scope and shows the result per category', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    const listener = async ({ request }: { request: Request }) => {
      if (request.method === 'POST') bodies.push(await request.clone().json())
    }
    server.events.on('request:start', listener)
    onTestFinished(() => server.events.removeListener('request:start', listener))
    const { dialog } = renderDialog()

    await user.click(await screen.findByRole('radio', { name: /Gründlich/ }))
    await user.click(within(dialog()).getByRole('button', { name: 'Prüfung starten' }))

    expect(await screen.findByText(/Mindestanforderung erfüllt/)).toBeInTheDocument()
    expect(bodies).toEqual([{ repetitions: 3 }])
    const table = screen.getByRole('table', { name: 'Ergebnis je Prüffrage' })
    expect(within(table).getByText('Sucht, wenn die Frage es verlangt')).toBeInTheDocument()
    expect(within(table).getAllByText('(Pflicht)')).toHaveLength(3)
    expect(within(dialog()).getByRole('radiogroup', { name: 'Erneut prüfen' })).toBeInTheDocument()
  })

  it('offers the result for adoption as measured „fähig“', async () => {
    server.use(http.get(LATEST, () => HttpResponse.json(mockCompletedToolCheck(model))))
    const user = userEvent.setup()
    const { onAdopt, dialog } = renderDialog()

    await user.click(await within(dialog()).findByRole('button', { name: 'Ergebnis übernehmen' }))

    expect(onAdopt).toHaveBeenCalledWith({
      value: 'CAPABLE',
      measurementRun: mockCompletedToolCheck(model).measurementRun,
    })
  })

  it('proposes „nicht fähig“ when the floor is missed', async () => {
    server.use(
      http.get(LATEST, () =>
        HttpResponse.json({ ...mockCompletedToolCheck(model), floorMet: false }),
      ),
    )
    const user = userEvent.setup()
    const { onAdopt, dialog } = renderDialog()

    expect(await screen.findByText(/Mindestanforderung nicht erfüllt/)).toBeInTheDocument()
    await user.click(within(dialog()).getByRole('button', { name: 'Ergebnis übernehmen' }))
    expect(onAdopt).toHaveBeenCalledWith(expect.objectContaining({ value: 'NOT_CAPABLE' }))
  })

  it('does not offer a result measured against an earlier address', async () => {
    server.use(
      http.get(LATEST, () =>
        HttpResponse.json({ ...mockCompletedToolCheck(model), currentCombination: false }),
      ),
    )
    const { dialog } = renderDialog()

    expect(await screen.findByText(/gilt für eine frühere Adresse/)).toBeInTheDocument()
    expect(within(dialog()).queryByRole('button', { name: 'Ergebnis übernehmen' })).toBeNull()
  })

  it('shows the progress of a running check and offers no second start', async () => {
    server.use(
      http.get(LATEST, () =>
        HttpResponse.json({
          ...mockCompletedToolCheck(model),
          status: 'RUNNING',
          completedRuns: 7,
          totalRuns: 28,
          categories: [],
          measurementRun: null,
        }),
      ),
    )
    const { dialog } = renderDialog()

    expect(await screen.findByText('Prüfung läuft: 7 von 28 Fällen bewertet.')).toBeInTheDocument()
    expect(within(dialog()).queryByRole('button', { name: 'Prüfung starten' })).toBeNull()
    expect(within(dialog()).queryByRole('button', { name: 'Ergebnis übernehmen' })).toBeNull()
  })

  it('names a failed check', async () => {
    server.use(
      http.get(LATEST, () =>
        HttpResponse.json({
          ...mockCompletedToolCheck(model),
          status: 'FAILED',
          failure: 'Das Modell ist nicht erreichbar.',
          categories: [],
        }),
      ),
    )
    renderDialog()

    expect(
      await screen.findByText(
        /Die letzte Prüfung ist gescheitert: Das Modell ist nicht erreichbar/,
      ),
    ).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Prüfung starten' })).toBeEnabled(),
    )
  })
})
