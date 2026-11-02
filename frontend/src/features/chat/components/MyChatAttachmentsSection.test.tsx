import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ChatAttachment, LibraryListResponse } from '@/shared/api/types'
import { renderWithProviders } from '@/test/test-utils'
import ConfirmHost from '@/shared/ui/feedback/ConfirmHost'
import { useLibraryStore } from '@/features/library'
import { listMyChatAttachments } from '../api/chatAttachmentApi'
import { seedMockChatAttachments } from '../mocks/chatAttachmentHandlers'
import MyChatAttachmentsSection from './MyChatAttachmentsSection'

const bound: ChatAttachment = {
  id: 'attachment-bound',
  spaceId: 'space-1',
  chatId: 'chat-1',
  chatTitle: 'Fristen im Bauantrag',
  fileName: 'Bescheid.pdf',
  sizeBytes: 2048,
  status: 'READY',
  mode: 'FULL_TEXT',
  createdAt: '2026-11-01T10:00:00Z',
}

const unbound: ChatAttachment = {
  id: 'attachment-unbound',
  spaceId: 'space-1',
  fileName: 'Entwurf.docx',
  sizeBytes: 1024,
  status: 'READY',
  createdAt: '2026-11-01T11:00:00Z',
  deletionDueAt: '2026-11-02T11:00:00Z',
}

function library(id: string, name: string, myRole: LibraryListResponse['myRole']) {
  return { id, name, myRole, sourceType: 'UPLOAD' } as LibraryListResponse
}

function renderSection() {
  return renderWithProviders(
    <>
      <MyChatAttachmentsSection />
      <ConfirmHost />
    </>,
  )
}

describe('MyChatAttachmentsSection', () => {
  beforeEach(() => {
    seedMockChatAttachments([unbound, bound])
    useLibraryStore.setState({
      libraries: [
        library('lib-edit', 'Bauakten', 'EDITOR'),
        library('lib-view', 'Nur lesen', 'VIEWER'),
      ],
      loadLibraries: async () => {},
    })
  })

  it('names the chat of a bound attachment and the deletion day of an unsent one', async () => {
    renderSection()

    expect(await screen.findByText('Bescheid.pdf')).toBeVisible()
    expect(screen.getByText(/im Chat „Fristen im Bauantrag“ · gelöscht mit dem Chat/)).toBeVisible()
    expect(screen.getByText(/noch nicht gesendet · wird am 02\.11\.2026 gelöscht/)).toBeVisible()
  })

  it('deletes an attachment after a confirmation', async () => {
    const user = userEvent.setup()
    renderSection()

    const row = (await screen.findByText('Entwurf.docx')).closest('li')!
    await user.click(within(row).getByRole('button', { name: 'Löschen' }))
    await user.click(await screen.findByRole('button', { name: 'Löschen', hidden: false }))

    await waitFor(() => expect(screen.queryByText('Entwurf.docx')).not.toBeInTheDocument())
    expect((await listMyChatAttachments()).map((entry) => entry.id)).toEqual(['attachment-bound'])
  })

  it('copies into a library only where the person may upload', async () => {
    const user = userEvent.setup()
    renderSection()

    const row = (await screen.findByText('Bescheid.pdf')).closest('li')!
    await user.click(within(row).getByRole('button', { name: 'In Bibliothek übernehmen' }))
    const dialog = await screen.findByRole('dialog', { name: 'In Bibliothek übernehmen' })
    await user.click(within(dialog).getByRole('combobox', { name: 'Bibliothek' }))

    expect(screen.getByRole('option', { name: 'Bauakten' })).toBeVisible()
    expect(screen.queryByRole('option', { name: 'Nur lesen' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'Bauakten' }))
    await user.click(within(dialog).getByRole('button', { name: 'Übernehmen' }))

    await waitFor(() =>
      expect(
        screen.queryByRole('dialog', { name: 'In Bibliothek übernehmen' }),
      ).not.toBeInTheDocument(),
    )
  })
})
