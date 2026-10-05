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

describe('ProfileChangePreview - was beim Speichern verworfen wird (#2249)', () => {
  it('names every discard as the server counts it and quotes its question', () => {
    renderWithProviders(
      <ProfileChangePreview
        impact={{
          ...impact,
          connectionsDiscarded: 0,
          secretsDiscarded: 1,
          configurationsChanged: 2,
          connectedAccountsEnded: { count: null, fewerThan: 5 },
          confirmation:
            'Die Änderung verwirft die Zugangsdaten von 1 Bibliothek sowie etwaiger verbundener Konten von Personen dieses Zugangs. Bitte bestätigen.',
        }}
        ownership="BOTH"
      />,
      { withRouter: true },
    )

    const discards = screen.getByTestId('profile-change-discards')
    expect(discards).toHaveTextContent(
      'Die gespeicherten Zugangsdaten von 1 Bibliothek werden verworfen und sind neu einzutragen.',
    )
    expect(discards).toHaveTextContent('Die verbundenen Konten von Personen enden (weniger als 5)')
    expect(discards).toHaveTextContent('Für 2 Bibliotheken ändert sich die Konfiguration')
    expect(discards).not.toHaveTextContent(/Token des Zugangs/)
    expect(screen.getByTestId('profile-change-confirmation')).toHaveTextContent(
      'Vor dem Speichern fragt OPAA noch einmal nach: „Die Änderung verwirft die Zugangsdaten von 1 Bibliothek',
    )
  })

  it('says that every private library is released where persons are no longer admitted', () => {
    renderWithProviders(
      <ProfileChangePreview
        impact={{
          ...impact,
          rejectedPrivateLibraries: null,
          connectedAccountsEnded: { count: null, fewerThan: 5 },
          confirmation:
            'Die Änderung verwirft die Zugangsdaten etwaiger verbundener Konten von Personen dieses Zugangs. Bitte bestätigen.',
        }}
        ownership="BOTH"
        ownershipAfter="LIBRARY"
      />,
      { withRouter: true },
    )

    expect(screen.getByTestId('profile-change-private-rejections')).toHaveTextContent(
      'Alle privaten Bibliotheken werden vom Zugang gelöst und ruhen',
    )
    expect(screen.getByTestId('profile-change-private-rejections')).not.toHaveTextContent(
      /ablehnt|nicht ausgewiesen/,
    )
    expect(screen.getByTestId('profile-change-discards')).toHaveTextContent(
      'Die verbundenen Konten von Personen enden (weniger als 5); der Zugang nimmt danach keine Konten mehr an.',
    )
    expect(screen.getByTestId('profile-change-discards')).not.toHaveTextContent(
      /verbindet sein Konto neu/,
    )
  })

  it('says that nothing is discarded where the server plans no discard', () => {
    renderWithProviders(
      <ProfileChangePreview
        impact={{
          ...impact,
          connectionsDiscarded: 0,
          secretsDiscarded: 0,
          configurationsChanged: 0,
          connectedAccountsEnded: null,
          confirmation: null,
        }}
        ownership="LIBRARY"
      />,
      { withRouter: true },
    )

    expect(screen.getByTestId('profile-change-discards')).toHaveTextContent(
      'Die Änderung verwirft keine Zugangsdaten, kein verbundenes Konto und keinen Abgleichstand.',
    )
    expect(screen.queryByTestId('profile-change-confirmation')).not.toBeInTheDocument()
  })
})
