import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import type { ConnectionProfileOption, SourceTypeDescriptor } from '../../types/api'
import ConnectionProfileSelect from './ConnectionProfileSelect'
import { OWN_ADDRESS, effectiveConnection, selectableConnections } from './connectionChoice'

const NEXTCLOUD: SourceTypeDescriptor = {
  type: 'NEXTCLOUD',
  displayName: 'Nextcloud',
  indexingRun: true,
  uploads: false,
  pushIntake: false,
  browsable: true,
  profileSupport: 'OPTIONAL',
  profileRequired: false,
  signIns: [{ method: 'PERSONAL_SECRET', ownerships: ['LIBRARY'], secretForm: 'TOKEN' }],
  profileDefaults: [{ key: 'shared', label: 'Freigaben einlesen', kind: 'BOOLEAN', choices: [] }],
  serverAddress: { schemes: ['https', 'http'] },
  creatable: true,
  creatableWithOwnAddress: true,
  locked: false,
}

const RELEASED: ConnectionProfileOption = {
  id: 'profile-intern',
  name: 'Nextcloud intern',
  sourceType: 'NEXTCLOUD',
  serverUrl: 'https://cloud.intern.example',
  authMethod: 'PERSONAL_SECRET',
  creatable: true,
  connectorDefaults: { shared: false },
}

const NOT_RELEASED: ConnectionProfileOption = {
  id: 'profile-partner',
  name: 'Nextcloud Partner',
  sourceType: 'NEXTCLOUD',
  serverUrl: 'https://cloud.partner.example',
  authMethod: 'PERSONAL_SECRET',
  creatable: false,
  creationNotice:
    'Der Zugang „Nextcloud Partner“ ist für Sie nicht freigegeben. Freigaben erteilt die Systemverwaltung.',
}

function renderSelect(
  descriptor: SourceTypeDescriptor,
  options: ConnectionProfileOption[],
  { onChange = vi.fn(), offerOwnAddress = true } = {},
) {
  const value = effectiveConnection(
    null,
    selectableConnections(descriptor, options, offerOwnAddress),
  )
  renderWithProviders(
    <ConnectionProfileSelect
      descriptor={descriptor}
      state={{ options, error: null, forbidden: false, loaded: true }}
      value={value}
      onChange={onChange}
      offerOwnAddress={offerOwnAddress}
      idPrefix="test"
    />,
  )
  return { value, onChange }
}

describe('ConnectionProfileSelect', () => {
  it('offers the own address and a released profile, with its address, sign-in and defaults', async () => {
    const user = userEvent.setup()
    const { value, onChange } = renderSelect(NEXTCLOUD, [RELEASED])

    expect(value).toBe(OWN_ADDRESS)
    expect(screen.getByRole('radio', { name: /Eigene Adresse/ })).toBeChecked()
    const profile = screen.getByRole('radio', { name: /Nextcloud intern/ })
    expect(profile).toBeEnabled()
    expect(profile).toHaveTextContent('https://cloud.intern.example')
    expect(profile).toHaveTextContent('Anmeldung: Persönliches Geheimnis')
    expect(profile).toHaveTextContent('Vorgaben: Freigaben einlesen: Nein')
    expect(
      screen.getByText('Zugänge legt die Systemverwaltung an und gibt sie frei.'),
    ).toBeVisible()

    await user.click(profile)
    expect(onChange).toHaveBeenCalledWith('profile-intern')
  })

  it('shows a profile that is not released, locked, with the notice naming who releases it', () => {
    renderSelect(NEXTCLOUD, [RELEASED, NOT_RELEASED])

    const partner = screen.getByRole('radio', { name: /Nextcloud Partner/ })
    expect(partner).toBeDisabled()
    expect(partner).toHaveTextContent(/nicht freigegeben\. Freigaben erteilt die Systemverwaltung/)
  })

  it('offers no own address while the type is usable only through profiles, and picks the first usable profile', () => {
    const { value } = renderSelect(
      { ...NEXTCLOUD, profileRequired: true, creatableWithOwnAddress: false },
      [NOT_RELEASED, RELEASED],
    )

    expect(value).toBe('profile-intern')
    expect(screen.queryByRole('radio', { name: /Eigene Adresse/ })).not.toBeInTheDocument()
    expect(
      screen.getByText(/Die Quellart „Nextcloud“ ist nur über einen Zugang nutzbar/),
    ).toBeVisible()
    expect(screen.getByRole('radio', { name: /Nextcloud intern/ })).toBeChecked()
  })

  it('names who sets up profiles instead of a dead end when there is no way at all', () => {
    const { value } = renderSelect(
      { ...NEXTCLOUD, profileRequired: true, creatableWithOwnAddress: false },
      [NOT_RELEASED],
    )

    expect(value).toBeNull()
    expect(screen.getByTestId('test-connection-none')).toHaveTextContent(
      'Für die Quellart „Nextcloud“ steht Ihnen kein Zugang zur Verfügung. Zugänge legt die Systemverwaltung an und gibt sie frei',
    )
    expect(screen.getByRole('radio', { name: /Nextcloud Partner/ })).toBeDisabled()
  })

  it('never offers the own address where it is not part of the choice', () => {
    renderSelect(NEXTCLOUD, [RELEASED], { offerOwnAddress: false })

    expect(screen.queryByRole('radio', { name: /Eigene Adresse/ })).not.toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Nextcloud intern/ })).toBeChecked()
  })

  it('names the missing right once, instead of an error beside a „no profile“ notice', () => {
    renderWithProviders(
      <ConnectionProfileSelect
        descriptor={NEXTCLOUD}
        state={{ options: [], error: 'Keine Berechtigung', forbidden: true, loaded: true }}
        value={null}
        onChange={vi.fn()}
        offerOwnAddress={false}
        idPrefix="test"
      />,
    )

    expect(screen.getByTestId('test-connection-error')).toHaveTextContent(
      'Dieses Anlegerecht erteilt die Systemverwaltung',
    )
    expect(screen.queryByTestId('test-connection-none')).not.toBeInTheDocument()
    expect(screen.queryByText('Keine Berechtigung')).not.toBeInTheDocument()
  })
})
