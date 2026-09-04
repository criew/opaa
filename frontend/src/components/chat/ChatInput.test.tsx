import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import ChatInput from './ChatInput'
import { useChatStore } from '../../stores/chatStore'
import { useLibraryStore } from '../../stores/libraryStore'
import { useSpaceStore } from '../../stores/spaceStore'
import { server } from '../../mocks/server'
import type { LibraryListResponse, SpaceLibraryAssociationResponse } from '../../types/api'

/**
 * #783 review: the earlier version of these tests spied on useSpaceStore.getState() and swapped in
 * a mocked loadLibraryAssociations - but zustand's set() shallow-merges by spreading the *current*
 * state object into a new one (Object.assign({}, state, partial)), so a spied property value, once
 * set, gets copied forward into every subsequent state object regardless of vi.restoreAllMocks() -
 * that call only restores the descriptor on the specific (by then stale) object it was originally
 * spied on, not the copy the store has moved on to. The mock silently outlived its own test and
 * broke an unrelated, later one that never touched the spy. Overriding the MSW handler per test
 * instead exercises the real api -> spaceStore -> ChatInput chain (also covers #783 review nit 2)
 * and cannot leak this way, since server.resetHandlers() (frontend/src/test/setup.ts) already runs
 * after every test.
 */
function mockAssociations(
  spaceId: string,
  response: {
    hasAssociations: boolean
    items: SpaceLibraryAssociationResponse[]
  },
) {
  server.use(http.get(`/api/v1/spaces/${spaceId}/libraries`, () => HttpResponse.json(response)))
}

const rechtsquellen: LibraryListResponse = {
  id: 'library-referat-50',
  name: 'Rechtsquellen Soziales',
  description: 'SGB II, SGB XII, VwVfG, Dienstanweisungen',
  ownerType: 'GROUP',
  visibility: 'SHARED',
  listed: true,
  myRole: 'MANAGER',
  sourceType: 'FILESYSTEM',
  documentCount: 431,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const dienstanweisungen: LibraryListResponse = {
  id: 'library-dienstanweisungen',
  name: 'Dienstanweisungen',
  description: 'Organisationsweite Vorgaben',
  ownerType: 'GROUP',
  visibility: 'ORGANIZATION',
  listed: true,
  myRole: 'VIEWER',
  sourceType: 'UPLOAD',
  documentCount: 87,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

describe('ChatInput', () => {
  beforeEach(() => {
    useChatStore.setState({
      scope: 'all',
      referencedLibraryIds: [],
      spaceId: null,
      chatId: null,
      metadataFilter: null,
    })
    useLibraryStore.setState({
      libraries: [rechtsquellen, dienstanweisungen],
      libraryDetails: {},
      isLoading: false,
      error: null,
    })
    useSpaceStore.setState({
      libraryAssociations: [],
      hasLibraryAssociations: false,
      isLoadingLibraryAssociations: false,
      libraryAssociationsSpaceId: null,
    })
  })

  // #782: a space curated via space<->library associations (#706) must narrow the @Alles-Wissen
  // scope line to the intersection of associated and readable libraries, not show every readable
  // library the user happens to have - the backend (ChatService#effectiveLibraryScope) already
  // narrows the actual search, so a wider count here is a pure display lie about what gets
  // searched. Each test below drives the real spaceStore#loadLibraryAssociations action through an
  // MSW override for its own space id (see mockAssociations above).
  describe('scope line for a space with library associations (#782)', () => {
    it('counts only the associated-and-readable intersection, not every readable library', async () => {
      mockAssociations('space-gewerbeamt', {
        hasAssociations: true,
        items: [
          {
            libraryId: rechtsquellen.id,
            libraryName: rechtsquellen.name,
            readableByCaller: true,
            createdByUserId: 'user-1',
            createdAt: '2026-03-01T10:00:00Z',
          },
        ],
      })
      useChatStore.setState({ scope: 'all', spaceId: 'space-gewerbeamt' })

      render(<ChatInput onSend={vi.fn()} />)

      expect(await screen.findByText(/1 zugeordneter lesbarer Bestand/)).toBeInTheDocument()
      // The old, wrong wording named both readable libraries here - it must be gone.
      expect(screen.queryByText(/2 lesbare Bestände/)).not.toBeInTheDocument()
    })

    it('shows the plural form for more than one associated-and-readable library', async () => {
      mockAssociations('space-gewerbeamt', {
        hasAssociations: true,
        items: [
          {
            libraryId: rechtsquellen.id,
            libraryName: rechtsquellen.name,
            readableByCaller: true,
            createdByUserId: 'user-1',
            createdAt: '2026-03-01T10:00:00Z',
          },
          {
            libraryId: dienstanweisungen.id,
            libraryName: dienstanweisungen.name,
            readableByCaller: true,
            createdByUserId: 'user-1',
            createdAt: '2026-03-01T10:00:00Z',
          },
        ],
      })
      useChatStore.setState({ scope: 'all', spaceId: 'space-gewerbeamt' })

      render(<ChatInput onSend={vi.fn()} />)

      expect(await screen.findByText(/2 zugeordnete lesbare Bestände/)).toBeInTheDocument()
    })

    // #783 review nit 3: "curated, but nothing the caller may read" must read like MessageBubble's
    // and SpacePage's own wording for the identical state, not a bare "0 zugeordnete Bestände".
    it('shows the established "kein Wissen verfügbar" notice when nothing associated is readable', async () => {
      mockAssociations('space-gewerbeamt', { hasAssociations: true, items: [] })
      useChatStore.setState({ scope: 'all', spaceId: 'space-gewerbeamt' })

      render(<ChatInput onSend={vi.fn()} />)

      expect(
        await screen.findByText('In diesem Space ist für Sie derzeit kein Wissen verfügbar.'),
      ).toBeInTheDocument()
      expect(screen.queryByText(/zugeordnete/)).not.toBeInTheDocument()
    })

    it('keeps the previous "all readable" wording for a space without any association', async () => {
      mockAssociations('space-gewerbeamt', { hasAssociations: false, items: [] })
      useChatStore.setState({ scope: 'all', spaceId: 'space-gewerbeamt' })

      render(<ChatInput onSend={vi.fn()} />)

      expect(await screen.findByText(/2 lesbare Bestände/)).toBeInTheDocument()
    })
  })

  // #783 review, finding 1 (🔴): a chat/space switch must not render the *previous* space's
  // association count for the new one, even for one render - neither while the new space's own
  // load is still in flight nor if it fails outright.
  describe("the scope line never shows another space's association data (#783)", () => {
    it("shows a neutral notice, not the previous space's number, right after switching spaceId", async () => {
      // Space A's associations already resolved and are sitting in the store - exactly the state
      // ChatInput would be in right after chatting in a curated space A.
      useSpaceStore.setState({
        hasLibraryAssociations: true,
        libraryAssociations: [
          {
            libraryId: rechtsquellen.id,
            libraryName: rechtsquellen.name,
            readableByCaller: true,
            createdByUserId: 'user-1',
            createdAt: '2026-03-01T10:00:00Z',
          },
        ],
        libraryAssociationsSpaceId: 'space-a',
      })
      // Space B's own load never resolves within this test.
      server.use(http.get('/api/v1/spaces/space-b/libraries', () => new Promise(() => {})))
      useChatStore.setState({ scope: 'all', spaceId: 'space-b' })

      render(<ChatInput onSend={vi.fn()} />)

      expect(await screen.findByText('Suchbereich wird ermittelt …')).toBeInTheDocument()
      expect(screen.queryByText(/1 zugeordneter/)).not.toBeInTheDocument()
    })

    // #783 review nit 1: a failed load must not silently read as "this space has no associations"
    // - that renders as "every readable library", the exact false claim #782 fixed.
    it('does not fall back to "every readable library" when the association load fails', async () => {
      server.use(
        http.get('/api/v1/spaces/space-gewerbeamt/libraries', () =>
          HttpResponse.json({ error: 'Netzwerkfehler' }, { status: 500 }),
        ),
      )
      useChatStore.setState({ scope: 'all', spaceId: 'space-gewerbeamt' })

      render(<ChatInput onSend={vi.fn()} />)

      expect(await screen.findByText('Suchbereich wird ermittelt …')).toBeInTheDocument()
      expect(screen.queryByText(/lesbare Bestände/)).not.toBeInTheDocument()
    })
  })

  // #782/#783: exercises the real api -> spaceStore -> ChatInput chain through the MSW handler
  // (mocks/handlers.ts) and its curated fixture (mockSpaceLibraryAssociations['space-phoenix']),
  // rather than mocking the store action away - the earlier mocked tests above cover the display
  // logic in isolation, this one covers that readableByCaller actually survives the wire.
  it('resolves the associated-and-readable count through the real api/store chain (#783 review nit 2)', async () => {
    useChatStore.setState({ scope: 'all', spaceId: 'space-phoenix' })

    render(<ChatInput onSend={vi.fn()} />)

    expect(await screen.findByText(/1 zugeordneter lesbarer Bestand/)).toBeInTheDocument()
  })

  it('shows the search-scope line for the default scope (#591, mockup 1a)', async () => {
    render(<ChatInput onSend={vi.fn()} />)
    expect(await screen.findByText(/Durchsucht:/)).toBeInTheDocument()
    expect(screen.getByText(/mit @ auf eine Quelle eingrenzen/)).toBeInTheDocument()
  })

  it('reflects chosen references in the scope line (#591)', async () => {
    useChatStore.setState({
      scope: 'libraries',
      referencedLibraryIds: ['library-referat-50', 'library-dienstanweisungen'],
    })
    render(<ChatInput onSend={vi.fn()} />)
    expect(await screen.findByText(/2 gewählte Bestände/)).toBeInTheDocument()
  })

  // #783 review, "vorbestehend" finding: ChatService#effectiveLibraryScope intersects even the
  // concrete-chip scope with the readable libraries (ChatService.java:249-251) - a reference the
  // caller can no longer read must not inflate the count the footer shows.
  it('counts only the readable references in the "libraries" scope line, not every referenced id', async () => {
    useChatStore.setState({
      scope: 'libraries',
      referencedLibraryIds: ['library-referat-50', 'library-removed'],
    })
    render(<ChatInput onSend={vi.fn()} />)
    expect(await screen.findByText(/1 gewählter Bestand/)).toBeInTheDocument()
  })

  it('names the empty scope honestly (#591)', () => {
    useChatStore.setState({ scope: 'none', referencedLibraryIds: [] })
    render(<ChatInput onSend={vi.fn()} />)
    expect(screen.getByText(/antwortet ohne Wissensbasis/)).toBeInTheDocument()
  })

  // #1070: the chat's sticky core-field filter is visible as removable chips next to the scope
  // chips; removing one chip drops only that field's condition.
  it('shows the active metadata filter as removable chips (#1070)', async () => {
    const user = userEvent.setup()
    const setMetadataFilter = vi.fn()
    useChatStore.setState({
      scope: 'all',
      metadataFilter: {
        documentTypes: ['VERMERK'],
        documentDateFrom: '2024-01-01',
        documentDateTo: '2024-12-31',
      },
      setMetadataFilter,
    })
    render(<ChatInput onSend={vi.fn()} />)

    expect(screen.getByTestId('metadata-filter-chip-document-type')).toHaveTextContent(
      'Dokumentart: VERMERK',
    )
    expect(screen.getByTestId('metadata-filter-chip-document-date')).toHaveTextContent(
      'Datum: 01.01.2024 – 31.12.2024',
    )
    expect(screen.getByRole('button', { name: 'Metadatenfilter setzen' })).toBeInTheDocument()

    // A chip's delete action is reachable by keyboard on the focused, labelled chip - the same
    // way the library chips are removed in the tests above.
    const dateChip = screen.getByRole('button', { name: 'Filter nach Datum entfernen' })
    dateChip.focus()
    await user.keyboard('{Backspace}')
    expect(setMetadataFilter).toHaveBeenCalledWith({ documentTypes: ['VERMERK'] })
  })

  it('offers no filter for the empty scope', () => {
    useChatStore.setState({ scope: 'none', referencedLibraryIds: [] })
    render(<ChatInput onSend={vi.fn()} />)
    expect(screen.queryByRole('button', { name: 'Metadatenfilter setzen' })).not.toBeInTheDocument()
  })

  it('labels library suggestions with the type badge (#591, mockup 1h)', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)
    const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')
    await user.type(input, '@Rechts')
    expect(await screen.findByText('Bibliothek · verengt die Suche')).toBeInTheDocument()
  })

  it('renders input field, send button and the @Alles-Wissen chip by default', () => {
    render(<ChatInput onSend={vi.fn()} />)
    expect(
      screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Nachricht senden')).toBeInTheDocument()
    expect(screen.getByText('@Alles-Wissen')).toBeInTheDocument()
  })

  it('calls onSend with trimmed text on button click', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)

    const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')
    fireEvent.change(input, { target: { value: 'Hello world' } })
    fireEvent.click(screen.getByLabelText('Nachricht senden'))

    expect(onSend).toHaveBeenCalledWith('Hello world')
  })

  it('calls onSend on Enter key', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)

    const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')
    fireEvent.change(input, { target: { value: 'Test' } })
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: false })

    expect(onSend).toHaveBeenCalledWith('Test')
  })

  it('does not send on Shift+Enter', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)

    const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')
    fireEvent.change(input, { target: { value: 'Test' } })
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true })

    expect(onSend).not.toHaveBeenCalled()
  })

  it('does not send empty messages', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)

    const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')
    fireEvent.change(input, { target: { value: '   ' } })
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: false })

    expect(onSend).not.toHaveBeenCalled()
  })

  it('disables input when disabled prop is true', () => {
    render(<ChatInput onSend={vi.fn()} disabled />)
    expect(
      screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen'),
    ).toBeDisabled()
  })

  describe('the three chip-bar states (#560)', () => {
    it('shows the @Alles-Wissen chip, removable, as the default state', async () => {
      const user = userEvent.setup()
      render(<ChatInput onSend={vi.fn()} />)

      const chip = screen.getByRole('button', { name: 'Referenz Alles-Wissen entfernen' })
      chip.focus()
      await user.keyboard('{Backspace}')

      expect(useChatStore.getState().scope).toBe('none')
    })

    it('shows concrete library chips when the scope is "libraries"', () => {
      useChatStore.setState({ scope: 'libraries', referencedLibraryIds: ['library-referat-50'] })
      render(<ChatInput onSend={vi.fn()} />)

      expect(screen.getByText('Rechtsquellen Soziales')).toBeInTheDocument()
      expect(screen.queryByText('@Alles-Wissen')).not.toBeInTheDocument()
    })

    it('shows a hint and a way back to @Alles-Wissen when the bar is empty', async () => {
      const user = userEvent.setup()
      useChatStore.setState({ scope: 'none', referencedLibraryIds: [] })
      render(<ChatInput onSend={vi.fn()} />)

      expect(screen.getByText('Antwortet ohne Dokumente.')).toBeInTheDocument()
      const backButton = screen.getByRole('button', { name: 'Wieder alles Wissen durchsuchen' })

      await user.click(backButton)

      expect(useChatStore.getState().scope).toBe('all')
      expect(useChatStore.getState().referencedLibraryIds).toEqual([])
    })

    it('does not show the empty-bar hint while scope is "all" or "libraries"', () => {
      render(<ChatInput onSend={vi.fn()} />)
      expect(screen.queryByText('Antwortet ohne Dokumente.')).not.toBeInTheDocument()

      useChatStore.setState({ scope: 'libraries', referencedLibraryIds: ['library-referat-50'] })
      render(<ChatInput onSend={vi.fn()} />)
      expect(screen.queryByText('Antwortet ohne Dokumente.')).not.toBeInTheDocument()
    })

    // #564 review: scope "libraries" with an id that is not (yet, or no longer) in the loaded
    // library list must never look like an emptied bar - that would be indistinguishable from a
    // deliberate "ohne Wissen" and silently drop the reference from what the user sees.
    it('shows a loading chip for a referenced id while the library list is still loading', () => {
      useChatStore.setState({ scope: 'libraries', referencedLibraryIds: ['library-referat-50'] })
      useLibraryStore.setState({
        libraries: [],
        libraryDetails: {},
        isLoading: true,
        error: null,
      })
      render(<ChatInput onSend={vi.fn()} />)

      expect(screen.getByLabelText('Bibliotheksreferenz wird geladen')).toBeInTheDocument()
      expect(screen.queryByText('Antwortet ohne Dokumente.')).not.toBeInTheDocument()
    })

    it('shows a removable placeholder chip for a referenced id that is no longer readable', async () => {
      const user = userEvent.setup()
      useChatStore.setState({
        scope: 'libraries',
        referencedLibraryIds: ['library-referat-50', 'library-removed'],
      })
      // The library list finished loading but no longer contains "library-removed" - it was
      // deleted, or is no longer readable by this user.
      render(<ChatInput onSend={vi.fn()} />)

      expect(screen.getByText('Rechtsquellen Soziales')).toBeInTheDocument()
      const placeholder = screen.getByRole('button', {
        name: 'Nicht verfügbare Bibliotheksreferenz entfernen',
      })
      expect(placeholder).toBeInTheDocument()

      placeholder.focus()
      await user.keyboard('{Backspace}')

      expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-referat-50'])
    })
  })

  describe('replacement logic', () => {
    it('replaces @Alles-Wissen with the first concrete chip selected via @', async () => {
      const user = userEvent.setup()
      render(<ChatInput onSend={vi.fn()} />)
      const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')

      await user.type(input, 'Bitte @Rechts')
      await user.click(await screen.findByRole('option', { name: /Rechtsquellen Soziales/ }))

      expect(useChatStore.getState().scope).toBe('libraries')
      expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-referat-50'])
      expect(screen.queryByText('@Alles-Wissen')).not.toBeInTheDocument()
    })

    it('replaces concrete chips when @Alles-Wissen is picked from the @ suggestions', async () => {
      const user = userEvent.setup()
      useChatStore.setState({ scope: 'libraries', referencedLibraryIds: ['library-referat-50'] })
      render(<ChatInput onSend={vi.fn()} />)
      const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')

      await user.type(input, '@Alles')
      await user.click(await screen.findByRole('option', { name: /@Alles-Wissen/ }))

      expect(useChatStore.getState().scope).toBe('all')
      expect(useChatStore.getState().referencedLibraryIds).toEqual([])
    })
  })

  it('opens library suggestions on "@", with @Alles-Wissen always listed first, and filters by further typing', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)
    const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')

    await user.type(input, '@')
    const listbox = await screen.findByRole('listbox', { name: 'Suchbereich' })
    const options = listbox.querySelectorAll('[role="option"]')
    expect(options[0]).toHaveTextContent('@Alles-Wissen')

    await user.type(input, 'Rechts')

    expect(
      await screen.findByRole('option', { name: /Rechtsquellen Soziales/ }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /Dienstanweisungen/ })).not.toBeInTheDocument()
    // The chip bar itself still shows @Alles-Wissen (scope hasn't changed yet) - only the
    // suggestion list must have filtered the special entry out.
    expect(screen.queryByRole('option', { name: /Alles-Wissen/ })).not.toBeInTheDocument()
  })

  it('selects a suggestion by click, adds a chip and removes the @-fragment from the text', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)
    const input = screen.getByPlaceholderText(
      'Frage stellen … mit @ auf eine Quelle eingrenzen',
    ) as HTMLTextAreaElement

    await user.type(input, 'Bitte @Rechts')
    await user.click(await screen.findByRole('option', { name: /Rechtsquellen Soziales/ }))

    expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-referat-50'])
    expect(input.value).toBe('Bitte ')
  })

  it('selects the highlighted suggestion via keyboard (arrow + Enter)', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)
    const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')

    await user.type(input, '@')
    await screen.findByRole('option', { name: /Rechtsquellen Soziales/ })
    // suggestion order is [@Alles-Wissen, rechtsquellen, dienstanweisungen] - three ArrowDown
    // presses land on the last option.
    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{Enter}')

    expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-dienstanweisungen'])
  })

  it('selects the hovered suggestion on click without prior keyboard navigation', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)
    const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')

    await user.type(input, '@')
    const option = await screen.findByRole('option', { name: /Dienstanweisungen/ })
    await user.hover(option)
    await user.click(option)

    expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-dienstanweisungen'])
  })

  it('does not select a suggestion on a plain Enter without prior highlight - it sends normally', async () => {
    const onSend = vi.fn()
    const user = userEvent.setup()
    render(<ChatInput onSend={onSend} />)
    const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')

    await user.type(input, 'Bitte @Rechts')
    await screen.findByRole('option', { name: /Rechtsquellen Soziales/ })
    await user.keyboard('{Enter}')

    expect(useChatStore.getState().referencedLibraryIds).toEqual([])
    expect(onSend).toHaveBeenCalledWith('Bitte @Rechts')
  })

  it('closes the suggestion list on Escape without sending', async () => {
    const onSend = vi.fn()
    const user = userEvent.setup()
    render(<ChatInput onSend={onSend} />)
    const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')

    await user.type(input, '@Rechts')
    await screen.findByRole('option', { name: /Rechtsquellen Soziales/ })
    await user.keyboard('{Escape}')

    expect(screen.queryByRole('option', { name: /Rechtsquellen Soziales/ })).not.toBeInTheDocument()
    expect(onSend).not.toHaveBeenCalled()
  })

  it('does not reopen the suggestion list while typing further inside a dismissed mention', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)
    const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')

    await user.type(input, '@Rechts')
    await screen.findByRole('option', { name: /Rechtsquellen Soziales/ })
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('option', { name: /Rechtsquellen Soziales/ })).not.toBeInTheDocument()

    // Still typing inside the same '@'-fragment must not reopen the list.
    await user.type(input, 'quellen')
    expect(screen.queryByRole('option', { name: /Rechtsquellen Soziales/ })).not.toBeInTheDocument()

    // Leaving the fragment (space) and starting a new one reopens suggestions again.
    await user.type(input, ' @Dienst')
    expect(await screen.findByRole('option', { name: /Dienstanweisungen/ })).toBeInTheDocument()
  })

  it('closes the suggestion list on a click outside the input and popup', async () => {
    const user = userEvent.setup()
    render(
      <div>
        <ChatInput onSend={vi.fn()} />
        <button type="button">Außerhalb</button>
      </div>,
    )
    const input = screen.getByPlaceholderText('Frage stellen … mit @ auf eine Quelle eingrenzen')

    await user.type(input, '@Rechts')
    await screen.findByRole('option', { name: /Rechtsquellen Soziales/ })

    await user.click(screen.getByRole('button', { name: 'Außerhalb' }))

    expect(screen.queryByRole('option', { name: /Rechtsquellen Soziales/ })).not.toBeInTheDocument()
  })

  it('renders referenced libraries as chips with an accessible name, removable via keyboard', async () => {
    const user = userEvent.setup()
    useChatStore.setState({ scope: 'libraries', referencedLibraryIds: ['library-referat-50'] })
    render(<ChatInput onSend={vi.fn()} />)

    expect(screen.getByText('Rechtsquellen Soziales')).toBeInTheDocument()
    // The default delete icon MUI renders carries aria-hidden, so the accessible name has to sit
    // on the chip itself (review finding #539) - getByLabelText would find the hidden icon's own
    // (unset) label and miss a regression there, so this specifically asserts the *chip*, exposed
    // via its role="button", carries the name. Deletion itself is exercised the way MUI's Chip
    // actually wires it for a focused, labelled chip: Backspace/Delete while focused, not a click
    // on the (visually present but accessibly hidden) icon.
    const chip = screen.getByRole('button', {
      name: 'Bibliotheksreferenz Rechtsquellen Soziales entfernen',
    })

    chip.focus()
    await user.keyboard('{Backspace}')

    expect(useChatStore.getState().referencedLibraryIds).toEqual([])
    expect(useChatStore.getState().scope).toBe('none')
  })
})
