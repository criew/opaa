import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import LibrarySourceSection from './LibrarySourceSection'

const library = {
  name: 'Wiki',
  sourceType: 'CONFLUENCE',
  sourceUrl: 'https://wiki.rheinfurt.example',
}

describe('LibrarySourceSection - Zugang (#2160)', () => {
  it('names the connection profile of the library', () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...library, connectionProfile: { id: 'p-1', name: 'Zugang Wiki intern' } }}
        canEditSource
      />,
    )

    expect(screen.getByTestId('connection-profile')).toHaveTextContent('Zugang: Zugang Wiki intern')
    expect(screen.queryByTestId('connection-profile-removed')).not.toBeInTheDocument()
  })

  it('says that the profile was removed and what happens to the content', () => {
    renderWithProviders(
      <LibrarySourceSection
        libraryId="library-1"
        library={{ ...library, connectionProfileRemoved: true }}
        canEditSource
      />,
    )

    expect(screen.getByTestId('connection-profile-removed')).toHaveTextContent(
      /Zugang entfernt.*bleibt durchsuchbar/,
    )
  })
})
