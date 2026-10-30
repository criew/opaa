import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { resetAllStores } from '@/shared/session/resettableStores'
import { renderWithProviders } from '../../../test/test-utils'
import { useDocumentStore } from '../stores/documentStore'
import LibraryDocumentsNotices from './LibraryDocumentsNotices'

function renderNotices() {
  return renderWithProviders(
    <LibraryDocumentsNotices
      libraryId="library-1"
      newFolderDialogOpen={false}
      renameFolderTarget={null}
    />,
  )
}

const rejected = [
  'Dateiformat wird nicht unterstützt. Erlaubt: .pdf, .docx (Datei: a.exe)',
  'Dateiformat wird nicht unterstützt. Erlaubt: .pdf, .docx (Datei: b.bat)',
  'Datei ist zu groß (Datei: c.pdf)',
]

beforeEach(() => {
  resetAllStores()
})

describe('LibraryDocumentsNotices - abgelehnte Dateien', () => {
  it('names the rejected files, grouped by reason', () => {
    useDocumentStore.setState({ uploadErrors: rejected })
    renderNotices()

    const notice = screen.getByRole('alert')
    expect(notice).toHaveTextContent('Nicht hochgeladen: a.exe, b.bat')
    expect(notice).toHaveTextContent('Dateiformat wird nicht unterstützt')
    expect(notice).not.toHaveTextContent('Erlaubt')
    expect(notice).toHaveTextContent('Nicht hochgeladen: c.pdf')
  })

  // regression guard: a quota rejection once lost its reason entirely, so a file larger than the
  // remaining quota of a nearly empty library stood there without any explanation.
  it('keeps a quota rejection whole, with how much is occupied', () => {
    useDocumentStore.setState({
      uploadErrors: [
        'Speicherkontingent der Bibliothek erschöpft (171 B von 3 MB belegt) (Datei: gross.txt)',
      ],
    })
    renderNotices()

    const notice = screen.getByRole('alert')
    expect(notice).toHaveTextContent('Nicht hochgeladen: gross.txt')
    expect(notice).toHaveTextContent(
      'Speicherkontingent der Bibliothek erschöpft (171 B von 3 MB belegt)',
    )
  })

  it('folds the notice into one line on „Schließen“ and unfolds it again', async () => {
    const user = userEvent.setup()
    useDocumentStore.setState({ uploadErrors: rejected })
    renderNotices()

    await user.click(screen.getByRole('button', { name: 'Schließen' }))

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Abgelehnte Dateien anzeigen (3)' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Verwerfen' })).toBeVisible()
    // folding keeps the files: the store still holds them until the next upload or „Verwerfen“
    expect(useDocumentStore.getState().uploadErrors).toEqual(rejected)

    await user.click(screen.getByRole('button', { name: 'Abgelehnte Dateien anzeigen (3)' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Nicht hochgeladen: a.exe, b.bat')
    expect(screen.queryByRole('button', { name: 'Verwerfen' })).not.toBeInTheDocument()
  })

  it('keeps the folded line at the place of the notice, above the other messages', async () => {
    const user = userEvent.setup()
    useDocumentStore.setState({ uploadErrors: rejected, deleteError: 'Löschen fehlgeschlagen' })
    const { container } = renderNotices()
    const before = container.textContent ?? ''
    expect(before.indexOf('Nicht hochgeladen')).toBeLessThan(
      before.indexOf('Löschen fehlgeschlagen'),
    )

    // the delete error is an Alert with its own „Schließen“; the upload notice comes first
    const [notice] = screen.getAllByRole('alert')
    await user.click(within(notice).getByRole('button', { name: 'Schließen' }))

    const after = container.textContent ?? ''
    expect(after.indexOf('Abgelehnte Dateien anzeigen')).toBeGreaterThanOrEqual(0)
    expect(after.indexOf('Abgelehnte Dateien anzeigen')).toBeLessThan(
      after.indexOf('Löschen fehlgeschlagen'),
    )
  })

  it('removes the folded notice on „Verwerfen“', async () => {
    const user = userEvent.setup()
    useDocumentStore.setState({ uploadErrors: rejected })
    renderNotices()

    await user.click(screen.getByRole('button', { name: 'Schließen' }))
    await user.click(screen.getByRole('button', { name: 'Verwerfen' }))

    expect(useDocumentStore.getState().uploadErrors).toEqual([])
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Abgelehnte Dateien anzeigen/ })).toBeNull()
  })

  it('counts a message without a file once', async () => {
    const user = userEvent.setup()
    useDocumentStore.setState({ uploadErrors: ['3 Dateien wurden übersprungen.', rejected[2]] })
    renderNotices()

    await user.click(screen.getByRole('button', { name: 'Schließen' }))

    expect(screen.getByRole('button', { name: 'Abgelehnte Dateien anzeigen (2)' })).toBeVisible()
  })

  it('shows the full notice again once a new upload adds rejected files', async () => {
    const user = userEvent.setup()
    useDocumentStore.setState({ uploadErrors: [rejected[0]] })
    renderNotices()

    await user.click(screen.getByRole('button', { name: 'Schließen' }))
    act(() => useDocumentStore.setState({ uploadErrors: [rejected[0], rejected[2]] }))

    expect(screen.getByRole('alert')).toHaveTextContent('Nicht hochgeladen: c.pdf')
  })
})
