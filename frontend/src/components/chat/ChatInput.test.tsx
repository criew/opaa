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
  ownerType: 'SYSTEM',
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
    await user.keyboard('{ArrowDown}{Enter}')

    expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-dienstanweisungen'])
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

  it('renders referenced libraries as removable chips', async () => {
    const user = userEvent.setup()
    useChatStore.setState({ referencedLibraryIds: ['library-referat-50'] })
    render(<ChatInput onSend={vi.fn()} />)

    expect(screen.getByText('Rechtsquellen Soziales')).toBeInTheDocument()

    await user.click(screen.getByLabelText('Bibliotheksreferenz Rechtsquellen Soziales entfernen'))

    expect(useChatStore.getState().referencedLibraryIds).toEqual([])
  })
})
