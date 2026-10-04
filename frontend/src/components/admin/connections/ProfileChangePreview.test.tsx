import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
import type { ConnectionProfileImpactResponse } from '../../../types/api'
import ProfileChangePreview from './ProfileChangePreview'

const impact: ConnectionProfileImpactResponse = {
  connections: 2,
  libraries: 2,
  connectedAccounts: { count: null, fewerThan: 5 },
  lastForProfileRequirement: false,
  rejectedLibraries: 0,
  rejections: [],
}

describe('ProfileChangePreview - private Bibliotheken (#2164)', () => {
  it('names refused private libraries as a masked number that does not block the change', () => {
    renderWithProviders(
      <ProfileChangePreview
        impact={{ ...impact, rejectedPrivateLibraries: { count: null, fewerThan: 5 } }}
        ownership="BOTH"
      />,
      { withRouter: true },
    )

    const line = screen.getByTestId('profile-change-private-rejections')
    expect(line).toHaveTextContent(
      'Private Bibliotheken, für die der Konnektor ablehnt: weniger als 5',
    )
    expect(line).toHaveTextContent(/verhindern die Änderung nicht/)
    // the shared libraries are still accepted - a refused private one is no veto
    expect(
      screen.getByText(/nimmt die Änderung für alle geteilten Bibliotheken an/),
    ).toBeInTheDocument()
  })

  it('shows an exact number where the API gives one', () => {
    renderWithProviders(
      <ProfileChangePreview
        impact={{ ...impact, rejectedPrivateLibraries: { count: 6, fewerThan: null } }}
        ownership="PERSON"
      />,
      { withRouter: true },
    )

    expect(screen.getByTestId('profile-change-private-rejections')).toHaveTextContent('ablehnt: 6')
  })

  it('says "nicht ausgewiesen" where the API withholds the number on a profile admitting persons', () => {
    renderWithProviders(
      <ProfileChangePreview
        impact={{ ...impact, rejectedPrivateLibraries: null }}
        ownership="BOTH"
      />,
      { withRouter: true },
    )

    expect(screen.getByTestId('profile-change-private-rejections')).toHaveTextContent(
      'ablehnt: nicht ausgewiesen',
    )
  })

  it('says nothing about private libraries on a profile admitting no persons', () => {
    renderWithProviders(<ProfileChangePreview impact={impact} ownership="LIBRARY" />, {
      withRouter: true,
    })

    expect(screen.queryByTestId('profile-change-private-rejections')).not.toBeInTheDocument()
  })
})
