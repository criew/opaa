import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatAttachment } from '@/shared/api/types'
import ChatInput from './ChatInput'
import { useAuthStore } from '@/shared/session/authStore'
import { useChatStore } from '../stores/chatStore'
import { useSpaceStore } from '@/features/space'

const api = vi.hoisted(() => ({
  uploadChatAttachment: vi.fn(),
  getChatAttachment: vi.fn(),
  deleteChatAttachment: vi.fn(async () => {}),
}))

vi.mock('../api/chatAttachmentApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/chatAttachmentApi')>()),
  uploadChatAttachment: api.uploadChatAttachment,
  getChatAttachment: api.getChatAttachment,
  deleteChatAttachment: api.deleteChatAttachment,
}))

function attachment(fileName: string, status: ChatAttachment['status']): ChatAttachment {
  return {
    id: `id-${fileName}`,
    spaceId: 'space-1',
    fileName,
    sizeBytes: 2048,
    status,
    createdAt: '2026-11-01T10:00:00Z',
  }
}

function pdf(name: string) {
  return new File(['%PDF'], name, { type: 'application/pdf' })
}

/** Files dropped, pasted or picked go up at once; the question waits until they are read. */
describe('ChatInput attachments', () => {
  beforeEach(() => {
    api.uploadChatAttachment.mockReset()
    api.getChatAttachment.mockReset()
    api.deleteChatAttachment.mockClear()
    api.uploadChatAttachment.mockImplementation(async (_space: string, file: File) =>
      attachment(file.name, 'READY'),
    )
    useAuthStore.setState({ features: { speechInput: false } })
    useSpaceStore.setState({
      spaces: [],
      assetAssociations: [],
      hasAssetAssociations: true,
      hasKnowledge: true,
      hasReadableKnowledge: true,
      isLoadingAssetAssociations: false,
      assetAssociationsSpaceId: null,
    })
    useChatStore.setState({
      chatId: null,
      spaceId: 'space-1',
      scope: 'all',
      referencedLibraryIds: [],
      metadataFilter: null,
    })
  })

  it('sends the picked files with the question and empties the draft', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn()
    render(<ChatInput onSend={onSend} />)

    await user.upload(screen.getByTestId('chat-attachment-input'), [pdf('Vertrag.pdf')])
    expect(await screen.findByRole('button', { name: /Anhang Vertrag\.pdf/ })).toBeVisible()
    await user.type(screen.getByRole('textbox'), 'Welche Frist gilt?{Enter}')

    expect(api.uploadChatAttachment).toHaveBeenCalledWith('space-1', expect.any(File))
    expect(onSend).toHaveBeenCalledWith('Welche Frist gilt?', undefined, [
      { id: 'id-Vertrag.pdf', fileName: 'Vertrag.pdf', sizeBytes: 4 },
    ])
    expect(screen.queryByRole('button', { name: /Anhang Vertrag\.pdf/ })).not.toBeInTheDocument()
  })

  it('holds the question back while a file is still read', async () => {
    api.uploadChatAttachment.mockImplementation(async (_space: string, file: File) =>
      attachment(file.name, 'PROCESSING'),
    )
    api.getChatAttachment.mockImplementation(async () => attachment('Scan.pdf', 'READY'))
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)

    await user.upload(screen.getByTestId('chat-attachment-input'), [pdf('Scan.pdf')])
    await user.type(screen.getByRole('textbox'), 'Was steht drin?')
    expect(await screen.findByRole('button', { name: /Scan\.pdf, wird gelesen/ })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Senden' })).toBeDisabled()

    await waitFor(() => expect(screen.getByRole('button', { name: 'Senden' })).toBeEnabled(), {
      timeout: 4000,
    })
    expect(api.getChatAttachment).toHaveBeenCalledWith('id-Scan.pdf')
  })

  it('keeps a file that cannot be read in the draft with its reason until it is removed', async () => {
    api.uploadChatAttachment.mockImplementation(async (_space: string, file: File) => ({
      ...attachment(file.name, 'FAILED'),
      failureReason: 'Die Datei enthält keinen lesbaren Text.',
    }))
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)

    await user.upload(screen.getByTestId('chat-attachment-input'), [pdf('Bild.pdf')])
    await user.type(screen.getByRole('textbox'), 'Frage')
    const chip = await screen.findByRole('button', { name: /keinen lesbaren Text/ })
    expect(screen.getByRole('button', { name: 'Senden' })).toBeDisabled()

    chip.focus()
    await user.keyboard('{Delete}')
    expect(api.deleteChatAttachment).toHaveBeenCalledWith('id-Bild.pdf')
    expect(screen.getByRole('button', { name: 'Senden' })).toBeEnabled()
  })

  it('takes files dropped onto the input', async () => {
    render(<ChatInput onSend={vi.fn()} />)

    fireEvent.drop(screen.getByRole('textbox'), {
      dataTransfer: { files: [pdf('Anlage.pdf')], types: ['Files'] },
    })

    expect(await screen.findByRole('button', { name: /Anhang Anlage\.pdf/ })).toBeVisible()
  })

  it('takes no more than ten files for one question', async () => {
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)

    await user.upload(
      screen.getByTestId('chat-attachment-input'),
      Array.from({ length: 11 }, (_, index) => pdf(`Teil-${index}.pdf`)),
    )

    expect(await screen.findByText('Höchstens 10 Anhänge je Frage.')).toBeVisible()
    await waitFor(() => expect(api.uploadChatAttachment).toHaveBeenCalledTimes(10))
  })

  it('uploads at most three files at once and queues the rest', async () => {
    const pending: Array<() => void> = []
    api.uploadChatAttachment.mockImplementation(
      (_space: string, file: File) =>
        new Promise<ChatAttachment>((resolve) =>
          pending.push(() => resolve(attachment(file.name, 'READY'))),
        ),
    )
    const user = userEvent.setup()
    render(<ChatInput onSend={vi.fn()} />)

    await user.upload(
      screen.getByTestId('chat-attachment-input'),
      Array.from({ length: 5 }, (_, index) => pdf(`Teil-${index}.pdf`)),
    )
    expect(api.uploadChatAttachment).toHaveBeenCalledTimes(3)

    pending.shift()!()
    await waitFor(() => expect(api.uploadChatAttachment).toHaveBeenCalledTimes(4))
  })

  it('gives a refused question its attachments back', async () => {
    const user = userEvent.setup()
    const onSend = vi.fn(async () => ({ restoreDraft: 'Welche Frist gilt?' }))
    render(<ChatInput onSend={onSend} />)

    await user.upload(screen.getByTestId('chat-attachment-input'), [pdf('Vertrag.pdf')])
    await screen.findByRole('button', { name: /Anhang Vertrag\.pdf/ })
    await user.type(screen.getByRole('textbox'), 'Welche Frist gilt?{Enter}')

    expect(await screen.findByRole('button', { name: /Anhang Vertrag\.pdf/ })).toBeVisible()
    expect(screen.getByRole('textbox')).toHaveValue('Welche Frist gilt?')
  })

  it('offers no attachments with the feature switched off', () => {
    useAuthStore.setState({ features: { speechInput: false, chatUpload: false } })
    render(<ChatInput onSend={vi.fn()} />)

    expect(screen.queryByRole('button', { name: 'Dateien anhängen' })).not.toBeInTheDocument()
  })
})
