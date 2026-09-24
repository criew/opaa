import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from '../../mocks/server'
import { mockAvailablePrompts } from '../../mocks/promptChatHandlers'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChatInput from './ChatInput'
import { germanDate, todayIso } from './promptTemplate'
import { useAuthStore } from '../../stores/authStore'
import { useChatStore } from '../../stores/chatStore'
import { useLibraryStore } from '../../stores/libraryStore'
import { useSpaceStore } from '../../stores/spaceStore'

/** The chat input's '/' command, against the MSW prompt fixtures. */
describe('ChatInput: inserting a prompt', () => {
  beforeEach(() => {
    useChatStore.setState({
      scope: 'all',
      referencedLibraryIds: [],
      spaceId: 'space-engineering',
      chatId: null,
      metadataFilter: null,
    })
    useLibraryStore.setState({ libraries: [], libraryDetails: {}, isLoading: false, error: null })
    useSpaceStore.setState({
      assetAssociations: [],
      hasAssetAssociations: false,
      assetAssociationsNarrowSearch: false,
      isLoadingAssetAssociations: false,
      assetAssociationsSpaceId: 'space-engineering',
    })
    useAuthStore.setState({
      user: {
        id: 'mock-user-id',
        email: 'erika.muster@example.org',
        displayName: 'Erika Muster',
        systemRole: 'USER',
      },
    })
  })

  function renderInput() {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)
    return { onSend, input: screen.getByRole('combobox') }
  }

  it('/zusam suggests /zusammenfassung, asks for the Stichtag and sends only on Enter', async () => {
    const user = userEvent.setup()
    const { onSend, input } = renderInput()

    await user.type(input, '/zusam')

    const option = await screen.findByRole('option', { name: /\/zusammenfassung/ })
    expect(option).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('option', { name: /\/dank/ })).not.toBeInTheDocument()
    expect(input).toHaveAttribute('aria-activedescendant', option.id)

    await user.keyboard('{Enter}')

    const dialog = await screen.findByRole('dialog', { name: /Zusammenfassung/ })
    const stichtag = within(dialog).getByLabelText(/Stichtag/)
    expect(stichtag).toBeRequired()
    expect(stichtag).toHaveValue(todayIso())
    expect(onSend).not.toHaveBeenCalled()

    await user.click(within(dialog).getByRole('button', { name: 'Einsetzen' }))

    const resolved = `Fasse den Stand des Vorgangs zum ${germanDate(todayIso())} zusammen. Umfang: kurz.`
    await waitFor(() => expect(input).toHaveValue(resolved))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByTestId('used-prompt-chip')).toHaveTextContent('Prompt: Zusammenfassung')
    expect(onSend).not.toHaveBeenCalled()

    await user.type(input, '{Enter}')

    expect(onSend).toHaveBeenCalledWith(resolved, {
      id: 'prompt-zusammenfassung',
      title: 'Zusammenfassung',
    })
  })

  it('a required field left empty blocks "Einsetzen"', async () => {
    const user = userEvent.setup()
    const { input } = renderInput()

    await user.type(input, '/anhoer')
    await screen.findByRole('option', { name: /\/anhoerung/ })
    await user.keyboard('{Enter}')

    const dialog = await screen.findByRole('dialog', { name: /Anhörungsschreiben/ })
    const insert = within(dialog).getByRole('button', { name: 'Einsetzen' })
    expect(insert).toBeDisabled()

    await user.type(within(dialog).getByLabelText(/Aktenzeichen/), '32-1/2026')

    expect(insert).toBeEnabled()
    await user.click(insert)
    await waitFor(() =>
      expect(input).toHaveValue(
        `Entwirf ein Anhörungsschreiben zum Aktenzeichen 32-1/2026, Stand ${germanDate(todayIso())}. Sachverhalt: `,
      ),
    )
  })

  it('inserts a prompt without variables at once; a removed chip sends no prompt', async () => {
    const user = userEvent.setup()
    const { onSend, input } = renderInput()

    await user.type(input, '/dank')
    await screen.findByRole('option', { name: /\/dank/ })
    await user.keyboard('{Enter}')

    const text = 'Formuliere ein kurzes, freundliches Dankesschreiben. Gezeichnet Erika Muster.'
    await waitFor(() => expect(input).toHaveValue(text))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    const chip = screen.getByTestId('used-prompt-chip')
    expect(chip).toHaveTextContent('Prompt: Dankesschreiben')

    await user.click(within(chip).getByTestId('CancelIcon'))
    expect(screen.queryByTestId('used-prompt-chip')).not.toBeInTheDocument()

    await user.type(input, '{Enter}')

    expect(onSend).toHaveBeenCalledTimes(1)
    expect(onSend.mock.calls[0]).toEqual([text])
  })

  it('groups by prompt library and puts the space-associated one first', async () => {
    const user = userEvent.setup()
    const { input } = renderInput()

    await user.type(input, '/')

    await screen.findByRole('option', { name: /\/zusammenfassung/ })
    const groups = within(screen.getByRole('listbox', { name: 'Prompts' })).getAllByRole('group')
    expect(groups.map((group) => group.getAttribute('aria-labelledby'))).toHaveLength(2)
    expect(groups[0]).toHaveAccessibleName(
      'Formulierungshilfen Referat 50 · diesem Space zugeordnet',
    )
    expect(groups[1]).toHaveAccessibleName('Hausweite Vorlagen')
    expect(within(groups[1]).getByRole('option', { name: /\/dank/ })).toBeInTheDocument()
  })

  it('navigates with the arrow keys and closes on Escape until the fragment is left', async () => {
    const user = userEvent.setup()
    const { input } = renderInput()

    await user.type(input, '/')
    await screen.findByRole('option', { name: /\/zusammenfassung/ })
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('option', { name: /\/anhoerung/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await user.keyboard('{ArrowUp}{ArrowUp}')
    expect(screen.getByRole('option', { name: /\/dank/ })).toHaveAttribute('aria-selected', 'true')

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox', { name: 'Prompts' })).not.toBeInTheDocument()
    await user.type(input, 'z')
    expect(screen.queryByRole('listbox', { name: 'Prompts' })).not.toBeInTheDocument()
    expect(input).toHaveAttribute('aria-expanded', 'false')
  })

  // Enter on an open '/' selection never sends the '/' text as a question - with no match, while
  // the list is still loading, or after Escape turned the fragment into plain text.
  it('Enter without a match sends nothing; after Escape the text is an ordinary question', async () => {
    const user = userEvent.setup()
    const { onSend, input } = renderInput()

    await user.type(input, '/zusamenfasung')
    await screen.findByText('Kein passender Prompt gefunden')
    await user.keyboard('{Enter}')

    expect(onSend).not.toHaveBeenCalled()
    expect(input).toHaveValue('/zusamenfasung')

    await user.keyboard('{Escape}{Enter}')

    expect(onSend).toHaveBeenCalledWith('/zusamenfasung')
  })

  it('Enter while the selection is still loading sends nothing', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    server.use(
      http.get('/api/v1/prompts/available', async () => {
        await gate
        return HttpResponse.json(mockAvailablePrompts('space-engineering'))
      }),
    )
    const user = userEvent.setup()
    const { onSend, input } = renderInput()

    await user.type(input, '/zu')
    await screen.findByText('Prompts werden geladen …')
    await user.keyboard('{Enter}')

    expect(onSend).not.toHaveBeenCalled()
    release()
    expect(await screen.findByRole('option', { name: /\/zusammenfassung/ })).toBeInTheDocument()
  })

  it('a prompt whose fetch is overtaken by typing is not inserted', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    server.use(
      http.get('/api/v1/prompt-libraries/:libraryId/prompts/:promptId', async () => {
        await gate
        return HttpResponse.json({
          id: 'prompt-dank',
          promptLibraryId: 'prompt-library-hausweit',
          name: 'dank',
          title: 'Dankesschreiben',
          text: 'Danke.',
          variables: [],
          sortOrder: 0,
          createdAt: '2026-09-20T08:00:00Z',
          updatedAt: '2026-09-20T08:00:00Z',
        })
      }),
    )
    const user = userEvent.setup()
    const { onSend, input } = renderInput()

    await user.type(input, '/dank')
    await screen.findByRole('option', { name: /\/dank/ })
    await user.keyboard('{Enter}')
    await user.keyboard('{Enter}')
    expect(onSend).not.toHaveBeenCalled()

    await user.type(input, ' und mehr')
    release()

    await waitFor(() => expect(input).toHaveValue('/dank und mehr'))
    expect(screen.queryByTestId('used-prompt-chip')).not.toBeInTheDocument()
  })

  it('a question refused for its prompt comes back into the input without the chip', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn().mockResolvedValue({ restoreDraft: 'Formuliere ein Danke.' })
    render(<ChatInput onSend={onSend} />)
    const input = screen.getByRole('combobox')

    await user.type(input, '/dank')
    await screen.findByRole('option', { name: /\/dank/ })
    await user.keyboard('{Enter}')
    await waitFor(() => expect(screen.getByTestId('used-prompt-chip')).toBeInTheDocument())
    await user.keyboard('{Enter}')

    await waitFor(() => expect(input).toHaveValue('Formuliere ein Danke.'))
    expect(screen.queryByTestId('used-prompt-chip')).not.toBeInTheDocument()
  })

  it('a slash inside a sentence is plain text', async () => {
    const user = userEvent.setup()
    const { input } = renderInput()

    await user.type(input, 'Frist nach /zusam')

    expect(screen.queryByRole('listbox', { name: 'Prompts' })).not.toBeInTheDocument()
  })
})
