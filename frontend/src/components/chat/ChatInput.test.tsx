import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import ChatInput from './ChatInput'
import { useChatStore } from '../../stores/chatStore'
import { useLibraryStore } from '../../stores/libraryStore'
import type { LibraryListResponse } from '../../types/api'

const rechtsquellen: LibraryListResponse = {
  id: 'library-referat-50',
  name: 'Rechtsquellen Soziales',
  description: 'SGB II, SGB XII, VwVfG, Dienstanweisungen',
  ownerType: 'GROUP',
  visibility: 'SHARED',
  listed: true,
  personal: false,
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
  personal: false,
  myRole: 'VIEWER',
  sourceType: 'UPLOAD',
  documentCount: 87,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

describe('ChatInput', () => {
  beforeEach(() => {
    useChatStore.setState({ useKnowledge: true, referencedLibraryIds: [] })
    useLibraryStore.setState({
      libraries: [rechtsquellen, dienstanweisungen],
      libraryDetails: {},
      isLoading: false,
      error: null,
    })
  })

  it('renders input field, send button and knowledge switch (on by default)', () => {
    render(<ChatInput onSend={vi.fn()} />)
    expect(screen.getByPlaceholderText('Stellen Sie eine Frage …')).toBeInTheDocument()
    expect(screen.getByLabelText('Nachricht senden')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Wissen nutzen' })).toBeChecked()
  })

  it('calls onSend with trimmed text on button click', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)

    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')
    fireEvent.change(input, { target: { value: 'Hello world' } })
    fireEvent.click(screen.getByLabelText('Nachricht senden'))

    expect(onSend).toHaveBeenCalledWith('Hello world')
  })

  it('calls onSend on Enter key', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)

    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')
    fireEvent.change(input, { target: { value: 'Test' } })
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: false })

    expect(onSend).toHaveBeenCalledWith('Test')
  })

  it('does not send on Shift+Enter', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)

    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')
    fireEvent.change(input, { target: { value: 'Test' } })
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true })

    expect(onSend).not.toHaveBeenCalled()
  })

  it('does not send empty messages', () => {
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)

    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')
    fireEvent.change(input, { target: { value: '   ' } })
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: false })

    expect(onSend).not.toHaveBeenCalled()
  })

  it('disables input and knowledge switch when disabled prop is true', () => {
    render(<ChatInput onSend={vi.fn()} disabled />)
    expect(screen.getByPlaceholderText('Stellen Sie eine Frage …')).toBeDisabled()
    expect(screen.getByRole('switch', { name: 'Wissen nutzen' })).toBeDisabled()
  })

  it('toggles the knowledge switch and updates the chat store', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)

    await user.click(screen.getByRole('switch', { name: 'Wissen nutzen' }))

    expect(useChatStore.getState().useKnowledge).toBe(false)
    expect(screen.getByRole('switch', { name: 'Wissen nutzen' })).not.toBeChecked()
  })

  it('shows a hint when knowledge is off and no library is referenced', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)

    await user.click(screen.getByRole('switch', { name: 'Wissen nutzen' }))

    expect(
      screen.getByText(/Ohne referenzierte Bibliotheken antwortet die KI ohne Wissensbasis/),
    ).toBeInTheDocument()
  })

  it('does not show the hint while knowledge is on', () => {
    render(<ChatInput onSend={vi.fn()} />)
    expect(
      screen.queryByText(/Ohne referenzierte Bibliotheken antwortet die KI ohne Wissensbasis/),
    ).not.toBeInTheDocument()
  })

  it('opens library suggestions on "@" and filters them by further typing', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)
    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')

    await user.type(input, '@Rechts')

    expect(await screen.findByText('Rechtsquellen Soziales')).toBeInTheDocument()
    expect(screen.queryByText('Dienstanweisungen')).not.toBeInTheDocument()
  })

  it('selects a suggestion by click, adds a chip and removes the @-fragment from the text', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)
    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …') as HTMLTextAreaElement

    await user.type(input, 'Bitte @Rechts')
    await user.click(await screen.findByText('Rechtsquellen Soziales'))

    expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-referat-50'])
    expect(input.value).toBe('Bitte ')
  })

  it('selects the highlighted suggestion via keyboard (arrow + Enter)', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)
    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')

    await user.type(input, '@')
    await screen.findByText('Rechtsquellen Soziales')
    // library order is [rechtsquellen, dienstanweisungen] - two ArrowDown presses land on the
    // second option.
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')

    expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-dienstanweisungen'])
  })

  it('selects the hovered suggestion on click without prior keyboard navigation', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)
    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')

    await user.type(input, '@')
    const option = await screen.findByText('Dienstanweisungen')
    await user.hover(option)
    await user.click(option)

    expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-dienstanweisungen'])
  })

  it('does not select a suggestion on a plain Enter without prior highlight - it sends normally', async () => {
    const onSend = vi.fn()
    const user = userEvent.setup()
    render(<ChatInput onSend={onSend} />)
    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')

    await user.type(input, 'Bitte @Rechts')
    await screen.findByText('Rechtsquellen Soziales')
    await user.keyboard('{Enter}')

    expect(useChatStore.getState().referencedLibraryIds).toEqual([])
    expect(onSend).toHaveBeenCalledWith('Bitte @Rechts')
  })

  it('closes the suggestion list on Escape without sending', async () => {
    const onSend = vi.fn()
    const user = userEvent.setup()
    render(<ChatInput onSend={onSend} />)
    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')

    await user.type(input, '@Rechts')
    await screen.findByText('Rechtsquellen Soziales')
    await user.keyboard('{Escape}')

    expect(screen.queryByText('Rechtsquellen Soziales')).not.toBeInTheDocument()
    expect(onSend).not.toHaveBeenCalled()
  })

  it('does not reopen the suggestion list while typing further inside a dismissed mention', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)
    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')

    await user.type(input, '@Rechts')
    await screen.findByText('Rechtsquellen Soziales')
    await user.keyboard('{Escape}')
    expect(screen.queryByText('Rechtsquellen Soziales')).not.toBeInTheDocument()

    // Still typing inside the same '@'-fragment must not reopen the list.
    await user.type(input, 'quellen')
    expect(screen.queryByText('Rechtsquellen Soziales')).not.toBeInTheDocument()

    // Leaving the fragment (space) and starting a new one reopens suggestions again.
    await user.type(input, ' @Dienst')
    expect(await screen.findByText('Dienstanweisungen')).toBeInTheDocument()
  })

  it('closes the suggestion list on a click outside the input and popup', async () => {
    const user = userEvent.setup()
    render(
      <div>
        <ChatInput onSend={vi.fn()} />
        <button type="button">Außerhalb</button>
      </div>,
    )
    const input = screen.getByPlaceholderText('Stellen Sie eine Frage …')

    await user.type(input, '@Rechts')
    await screen.findByText('Rechtsquellen Soziales')

    await user.click(screen.getByRole('button', { name: 'Außerhalb' }))

    expect(screen.queryByText('Rechtsquellen Soziales')).not.toBeInTheDocument()
  })

  it('renders referenced libraries as chips with an accessible name, removable via keyboard', async () => {
    const user = userEvent.setup()
    useChatStore.setState({ referencedLibraryIds: ['library-referat-50'] })
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
  })
})
