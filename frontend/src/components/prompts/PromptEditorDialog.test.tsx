import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from '../../mocks/server'
import { renderWithProviders, setMockAuthState } from '../../test/test-utils'
import { usePromptLibraryStore } from '../../stores/promptLibraryStore'
import type { PromptRequest, PromptResponse } from '../../types/api'
import PromptEditorDialog from './PromptEditorDialog'

const LIBRARY_ID = 'prompt-library-referat-50'

function renderEditor(prompt: PromptResponse | null = null) {
  const onClose = vi.fn()
  renderWithProviders(
    <PromptEditorDialog open promptLibraryId={LIBRARY_ID} prompt={prompt} onClose={onClose} />,
  )
  return { onClose }
}

/** Captures the body of every prompt POST, so a test can prove none went out. */
function capturePromptPosts(): PromptRequest[] {
  const bodies: PromptRequest[] = []
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'POST' && request.url.endsWith(`/${LIBRARY_ID}/prompts`)) {
      bodies.push((await request.clone().json()) as PromptRequest)
    }
  })
  return bodies
}

describe('PromptEditorDialog', () => {
  beforeEach(() => {
    setMockAuthState()
    usePromptLibraryStore.getState().reset()
    server.events.removeAllListeners()
  })

  it('derives the command from the title and shows how it is called', async () => {
    const user = userEvent.setup()
    renderEditor()

    await user.type(screen.getByLabelText('Titel'), 'Anhörung Bußgeld')

    expect(screen.getByLabelText('Befehl')).toHaveValue('anhoerung-bussgeld')
    expect(screen.getByText('/anhoerung-bussgeld')).toBeInTheDocument()
  })

  it('lists one variable row per placeholder and highlights the placeholders', async () => {
    const user = userEvent.setup()
    renderEditor()

    await user.click(screen.getByLabelText('Text'))
    // Blanks inside the braces and a system variable in any case are what the server normalises.
    await user.paste('Frist {{ frist }} für {{user_name}}, dazu {{fal sch}}')

    const table = screen.getByRole('table', { name: 'Variablen' })
    expect(within(table).getByText('{{frist}}')).toBeInTheDocument()
    // System variables are resolved on insertion and never get a row of their own.
    expect(within(table).queryByText(/USER_NAME/i)).not.toBeInTheDocument()

    const highlight = screen.getByRole('region', { name: 'Text mit hervorgehobenen Platzhaltern' })
    expect(within(highlight).getByTitle('Variable')).toHaveTextContent('{{ frist }}')
    expect(within(highlight).getByTitle('Systemvariable')).toHaveTextContent('{{user_name}}')
    expect(within(highlight).getByTitle('Ungültiger Platzhalter')).toHaveTextContent('{{fal sch}}')
  })

  // Several typing sequences take longer than the default 5s under full-suite CPU contention.
  it('stops an invalid prompt before sending it and names every problem', async () => {
    const posts = capturePromptPosts()
    const user = userEvent.setup()
    renderEditor()

    await user.type(screen.getByLabelText('Titel'), 'Vermerk')
    await user.clear(screen.getByLabelText('Befehl'))
    await user.type(screen.getByLabelText('Befehl'), 'Vermerk')
    await user.click(screen.getByLabelText('Text'))
    await user.paste('Bitte {{ kein name }}')
    await user.click(screen.getByRole('button', { name: 'Prompt speichern' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Der Name eines Prompts besteht aus Kleinbuchstaben')
    expect(alert).toHaveTextContent('Ungültiger Platzhalter „{{kein name}}“')
    expect(posts).toHaveLength(0)
  }, 15000)

  it('shows what the server still refuses, with its field errors', async () => {
    server.use(
      http.post(`/api/v1/prompt-libraries/${LIBRARY_ID}/prompts`, () =>
        HttpResponse.json(
          {
            error: 'Ein Prompt mit dem Namen „vermerk“ gibt es in dieser Bibliothek bereits.',
            fieldErrors: [{ field: 'name', message: 'bereits vergeben' }],
          },
          { status: 409 },
        ),
      ),
    )
    const user = userEvent.setup()
    const { onClose } = renderEditor()

    await user.type(screen.getByLabelText('Titel'), 'Vermerk')
    await user.type(screen.getByLabelText('Text'), 'Fasse zusammen.')
    await user.click(screen.getByRole('button', { name: 'Prompt speichern' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('gibt es in dieser Bibliothek bereits')
    expect(alert).toHaveTextContent('name: bereits vergeben')
    expect(onClose).not.toHaveBeenCalled()
  })

  it('saves a prompt with a required date variable', async () => {
    const posts = capturePromptPosts()
    const user = userEvent.setup()
    const { onClose } = renderEditor()

    await user.type(screen.getByLabelText('Titel'), 'Anhörung mit Frist')
    await user.click(screen.getByLabelText('Text'))
    await user.paste('Anhörung mit Frist {{frist}}.')
    const label = screen.getByLabelText('Beschriftung von frist')
    await user.clear(label)
    await user.type(label, 'Frist')
    await user.click(screen.getByRole('combobox', { name: 'Typ von frist' }))
    await user.click(await screen.findByRole('option', { name: 'Datum' }))
    await user.click(screen.getByLabelText('frist ist Pflicht'))
    await user.click(screen.getByRole('button', { name: 'Prompt speichern' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(posts).toHaveLength(1)
    expect(posts[0]).toMatchObject({
      name: 'anhoerung-mit-frist',
      title: 'Anhörung mit Frist',
      text: 'Anhörung mit Frist {{frist}}.',
      variables: [{ name: 'frist', label: 'Frist', type: 'DATE', required: true }],
    })
  }, 15000)

  it('previews the text with example values', async () => {
    const user = userEvent.setup()
    renderEditor()

    await user.click(screen.getByLabelText('Text'))
    await user.paste('Aktenzeichen {{az}} bitte prüfen.')
    await user.type(screen.getByLabelText('Beispielwert für az'), 'II-50/2026')

    expect(
      screen.getByRole('region', { name: 'Vorschau des eingesetzten Textes' }),
    ).toHaveTextContent('Aktenzeichen II-50/2026 bitte prüfen.')
  })
})
