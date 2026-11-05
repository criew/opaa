import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@/test/test-utils'
import { mockLibraryDetails } from '../mocks/libraryFixtures'
import LibrarySharingTab from './LibrarySharingTab'

function render(canEdit: boolean) {
  const library = mockLibraryDetails['library-mine']
  return renderWithProviders(
    <LibrarySharingTab
      libraryId={library.id}
      library={library}
      details={library}
      privateLibrary={false}
      canEdit={canEdit}
      externalAccessEnabled
      shareCapVisible={false}
      onTransferred={vi.fn(async () => {})}
      onSaveShareCap={vi.fn(async () => {})}
      diagnosticsLockSaving={false}
      diagnosticsLockError={null}
      onToggleDiagnosticsLock={vi.fn()}
      onDismissDiagnosticsLockError={vi.fn()}
    />,
    { withRouter: true },
  )
}

describe('LibrarySharingTab', () => {
  it('zeigt der Verwaltung die Zeile „Dienstkonten“', async () => {
    render(true)

    expect(await screen.findByRole('heading', { name: 'Dienstkonten' })).toBeInTheDocument()
  })

  it('zeigt Lesenden keine Zeile „Dienstkonten“', async () => {
    render(false)

    expect(await screen.findByRole('heading', { name: 'Eigentümer' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Dienstkonten' })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Verwalten/ })).toBeNull()
  })
})
