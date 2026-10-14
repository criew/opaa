import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MenuBookOutlinedIcon from '@mui/icons-material/MenuBookOutlined'
import { server } from '../../../mocks/server'
import { useAuthStore } from '@/shared/session/authStore'
import { useChatStore } from '../stores/chatStore'
import { useLibraryStore } from '@/features/library'
import { useSpaceStore } from '@/features/space'
import ChatInput from './ChatInput'
import type { ChatActionSource } from './chatActions'
import { promptActionSource } from './promptActionSource'

/**
 * The '/' menu as the chat input's one collection point for actions (#2588): it lists what the
 * active action sources contribute. Prompt libraries are one source; switched off, they contribute
 * nothing, and without any source '/' opens nothing and the hint does not offer it.
 */
describe('ChatInput: the "/" menu collects the active action sources', () => {
  let availableRequests = 0
  const countAvailable = ({ request }: { request: Request }) => {
    if (new URL(request.url).pathname === '/api/v1/prompts/available') availableRequests++
  }

  beforeEach(() => {
    availableRequests = 0
    server.events.on('request:start', countAvailable)
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
      features: {},
    })
  })

  afterEach(() => {
    server.events.removeListener('request:start', countAvailable)
    useAuthStore.setState({ features: {} })
  })

  const glossary: ChatActionSource = {
    id: 'glossary',
    Icon: MenuBookOutlinedIcon,
    texts: {
      heading: 'Begriff einsetzen',
      listLabel: 'Begriffe',
      loading: 'Begriffe werden geladen …',
      none: 'Keine Begriffe vorhanden',
      noMatch: 'Kein passender Begriff gefunden',
      loadFailed: 'Begriffe konnten nicht geladen werden',
      chooseFailed: 'Der Begriff konnte nicht geladen werden',
    },
    load: async () => [
      {
        id: 'fristen',
        group: { id: 'glossar', label: 'Glossar' },
        command: 'fristen',
        title: 'Fristenregel',
        description: null,
      },
    ],
    choose: async () => ({ kind: 'insert', text: 'Fristen laufen ab Zustellung.' }),
    markLabel: (mark) => mark.title,
  }

  it('names "/" in the hint while prompt libraries are on', async () => {
    render(<ChatInput onSend={vi.fn()} />)

    expect(await screen.findByText('@ für Quellen, / für Aktionen')).toBeInTheDocument()
  })

  it('opens no menu, loads no prompt and sends "/" text as a question while switched off', async () => {
    useAuthStore.setState({ features: { promptLibraries: false } })
    const onSend = vi.fn()
    const user = userEvent.setup()
    render(<ChatInput onSend={onSend} />)
    const input = screen.getByRole('combobox')

    expect(await screen.findByText('@ für Quellen')).toBeInTheDocument()
    expect(screen.queryByText(/\/ für Aktionen/)).not.toBeInTheDocument()

    await user.type(input, '/zusam')

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(screen.queryByText('Prompt einsetzen')).not.toBeInTheDocument()
    expect(input).toHaveAttribute('aria-expanded', 'false')
    expect(availableRequests).toBe(0)

    await user.keyboard('{Enter}')
    expect(onSend).toHaveBeenCalledWith('/zusam')
  })

  it('takes a further source without any change to the input itself', async () => {
    useAuthStore.setState({ features: { promptLibraries: false } })
    const onSend = vi.fn()
    const user = userEvent.setup()
    render(<ChatInput onSend={onSend} actionSources={[glossary]} />)
    const input = screen.getByRole('combobox')

    expect(await screen.findByText('@ für Quellen, / für Aktionen')).toBeInTheDocument()
    await user.type(input, '/fri')

    const option = await screen.findByRole('option', { name: /\/fristen/ })
    expect(
      within(screen.getByRole('listbox', { name: 'Begriffe' })).getByRole('group'),
    ).toHaveAccessibleName('Glossar')
    expect(option).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{Enter}')

    await waitFor(() => expect(input).toHaveValue('Fristen laufen ab Zustellung.'))
    await user.keyboard('{Enter}')
    expect(onSend).toHaveBeenCalledWith('Fristen laufen ab Zustellung.')
    expect(availableRequests).toBe(0)
  })

  it('lists every active source side by side under one generic heading', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} actionSources={[promptActionSource, glossary]} />)

    await user.type(screen.getByRole('combobox'), '/')

    expect(await screen.findByRole('option', { name: /\/fristen/ })).toBeInTheDocument()
    expect(await screen.findByRole('option', { name: /\/vermerk/ })).toBeInTheDocument()
    expect(screen.getByRole('listbox', { name: 'Aktionen' })).toBeInTheDocument()
  })

  // Only the prompt source's mark becomes usedPromptId; another source's mark is no prompt id.
  it('shows the mark of another source as a chip but does not send it as the prompt', async () => {
    const onSend = vi.fn()
    const user = userEvent.setup()
    const marking: ChatActionSource = {
      ...glossary,
      choose: async () => ({
        kind: 'insert',
        text: 'Fristen laufen ab Zustellung.',
        mark: { id: 'fristen', title: 'Fristenregel' },
      }),
    }
    render(<ChatInput onSend={onSend} actionSources={[marking]} />)
    const input = screen.getByRole('combobox')

    await user.type(input, '/fri')
    await screen.findByRole('option', { name: /\/fristen/ })
    await user.keyboard('{Enter}')

    await waitFor(() =>
      expect(screen.getByTestId('used-prompt-chip')).toHaveTextContent('Fristenregel'),
    )
    await user.keyboard('{Enter}')
    expect(onSend.mock.calls[0]).toEqual(['Fristen laufen ab Zustellung.'])
  })

  it('leaves out a source whose feature is switched off', async () => {
    server.use(http.get('/api/v1/prompts/available', () => HttpResponse.json([])))
    useAuthStore.setState({ features: { promptLibraries: false } })
    const user = userEvent.setup()
    render(
      <ChatInput onSend={vi.fn()} actionSources={[{ ...glossary, feature: 'promptLibraries' }]} />,
    )

    expect(await screen.findByText('@ für Quellen')).toBeInTheDocument()
    await user.type(screen.getByRole('combobox'), '/')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })
})
