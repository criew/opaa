import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '@/test/test-utils'
import LibraryQuotaNotice from './LibraryQuotaNotice'

const GIB = 1024 * 1024 * 1024

describe('LibraryQuotaNotice', () => {
  it('says that a shared library above its quota keeps its documents and takes in nothing more', () => {
    renderWithProviders(
      <LibraryQuotaNotice usedBytes={6 * GIB} quotaBytes={5 * GIB} privateLibrary={false} />,
    )

    const notice = screen.getByTestId('library-quota-over')
    expect(notice).toHaveTextContent(
      'Diese Bibliothek belegt mehr als ihr Speicherkontingent (6 GB von 5 GB).',
    )
    expect(notice).toHaveTextContent(
      'Alle Dokumente bleiben erhalten und durchsuchbar; Neues und größer gewordene Dateien nimmt sie erst wieder auf, wenn sie unter dem Kontingent liegt.',
    )
    expect(notice).toHaveTextContent('Das Kontingent legt die Systemverwaltung fest.')
  })

  it('names the operation as the one that sets the quota of a private library', () => {
    renderWithProviders(
      <LibraryQuotaNotice usedBytes={6 * GIB} quotaBytes={5 * GIB} privateLibrary />,
    )

    expect(screen.getByTestId('library-quota-over')).toHaveTextContent(
      'Das Kontingent je Bibliothek legt der Betrieb fest.',
    )
  })

  it.each([
    ['at its quota', 5 * GIB, 5 * GIB],
    ['below its quota', 1 * GIB, 5 * GIB],
    ['without a quota', 6 * GIB, 0],
    ['for a caller who does not see the figures', null, null],
  ])('stays away %s', (_label, usedBytes, quotaBytes) => {
    renderWithProviders(
      <LibraryQuotaNotice usedBytes={usedBytes} quotaBytes={quotaBytes} privateLibrary={false} />,
    )

    expect(screen.queryByTestId('library-quota-over')).not.toBeInTheDocument()
  })
})
