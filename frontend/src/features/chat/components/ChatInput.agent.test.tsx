import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChatInput from './ChatInput'
import { useAuthStore } from '@/shared/session/authStore'
import { useRuntimeSwitchStore } from '@/shared/session/runtimeSwitches'
import { useChatStore } from '../stores/chatStore'
import { useLibraryStore } from '@/features/library'
import { useSpaceStore } from '@/features/space'

/** The '@' menu offers agents as a group of their own; a chosen agent goes with the question. */
describe('ChatInput: choosing an agent', () => {
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
      hasKnowledge: false,
      hasReadableKnowledge: false,
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
    useRuntimeSwitchStore.setState({ agents: true })
  })

  function renderInput() {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)
    return { onSend, input: screen.getByRole('combobox') }
  }

  it('lists agents under their own heading, set apart from knowledge', async () => {
    const user = userEvent.setup()
    const { input } = renderInput()

    await user.type(input, '@')

    const agents = await screen.findByRole('group', { name: 'Agenten' })
    const knowledge = screen.getByRole('group', { name: 'Wissen' })
    expect(
      within(agents).getByRole('option', { name: /^Recherche\s*Agent · ersetzt den Suchbereich$/ }),
    ).toBeInTheDocument()
    expect(within(knowledge).getByRole('option', { name: /@Space-Wissen/ })).toBeInTheDocument()
    expect(within(knowledge).queryByRole('option', { name: /Recherche/ })).not.toBeInTheDocument()
    expect(screen.getByRole('listbox')).toHaveAccessibleName('Suchbereich oder Agent')
  })

  it('sends the chosen agent with the question and forgets it afterwards', async () => {
    const user = userEvent.setup()
    const { onSend, input } = renderInput()

    await user.type(input, '@Rech')
    await user.click(await screen.findByRole('option', { name: /^Recherche/ }))

    const chip = screen.getByTestId('chosen-agent-chip')
    expect(chip).toHaveTextContent('Agent: Recherche')
    expect(chip).toHaveAccessibleName('Agent Recherche entfernen')

    await user.type(input, 'Welche Fristen gelten?{Enter}')

    expect(onSend).toHaveBeenCalledWith('Welche Fristen gelten?', undefined, [], {
      id: 'agent-recherche',
      name: 'Recherche',
    })
    expect(screen.queryByTestId('chosen-agent-chip')).not.toBeInTheDocument()
  })

  it('gives the agent back with a refused question, but not an agent that was refused', async () => {
    const user = userEvent.setup()
    const onSend = vi
      .fn()
      .mockResolvedValueOnce({ restoreDraft: 'Erste Frage', keepAgent: true })
      .mockResolvedValueOnce({ restoreDraft: 'Zweite Frage', keepAgent: false })
    render(<ChatInput onSend={onSend} />)
    const input = screen.getByRole('combobox')

    await user.type(input, '@Rech')
    await user.click(await screen.findByRole('option', { name: /^Recherche/ }))
    await user.type(input, 'Erste Frage{Enter}')

    await waitFor(() => expect(input).toHaveValue('Erste Frage'))
    expect(screen.getByTestId('chosen-agent-chip')).toHaveTextContent('Agent: Recherche')

    await user.clear(input)
    await user.type(input, 'Zweite Frage{Enter}')

    await waitFor(() => expect(input).toHaveValue('Zweite Frage'))
    expect(screen.queryByTestId('chosen-agent-chip')).not.toBeInTheDocument()
  })

  it('removes the chosen agent with its chip', async () => {
    const user = userEvent.setup()
    const { onSend, input } = renderInput()

    await user.type(input, '@Rech')
    await user.click(await screen.findByRole('option', { name: /^Recherche/ }))
    await user.click(within(screen.getByTestId('chosen-agent-chip')).getByTestId('CancelIcon'))
    await user.type(input, 'Frage{Enter}')

    expect(onSend).toHaveBeenCalledWith('Frage')
  })

  it('offers only knowledge while agents are switched off', async () => {
    useRuntimeSwitchStore.setState({ agents: false })
    const requested = vi.fn()
    server.use(
      http.get('/api/v1/agents/available', () => {
        requested()
        return HttpResponse.json([])
      }),
    )
    const user = userEvent.setup()
    const { input } = renderInput()

    await user.type(input, '@')

    expect(await screen.findByRole('option', { name: /@Space-Wissen/ })).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Agenten' })).not.toBeInTheDocument()
    expect(screen.getByRole('listbox')).toHaveAccessibleName('Suchbereich')
    await waitFor(() => expect(requested).not.toHaveBeenCalled())
  })
})
