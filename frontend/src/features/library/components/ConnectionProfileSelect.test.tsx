import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
import type { ConnectionProfileOption, SourceTypeDescriptor } from '@/shared/api/types'
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
  ownership: 'LIBRARY',
  ownAccount: false,
  connectorDefaults: { shared: false },
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
  it('lists the released profiles with their host first and the own address last', async () => {
    const user = userEvent.setup()
    const { value, onChange } = renderSelect(NEXTCLOUD, [RELEASED])

    expect(value).toBe('profile-intern')
    const radios = screen.getAllByRole('radio')
    expect(radios).toHaveLength(2)
    expect(radios[0]).toHaveAccessibleName(/Nextcloud intern/)
    expect(radios[1]).toHaveAccessibleName('Eigene Adresse')
    expect(screen.getByRole('radio', { name: /Nextcloud intern/ })).toBeChecked()
    expect(screen.getByText('cloud.intern.example')).toBeVisible()
    expect(screen.getByRole('radiogroup', { name: 'Zugang' })).toBeVisible()
    // no tiles: neither sign-in, defaults nor who sets up profiles are explained
    expect(screen.queryByText(/Anmeldung:/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Vorgaben:/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Systemverwaltung/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: 'Eigene Adresse' }))
    expect(onChange).toHaveBeenCalledWith(OWN_ADDRESS)
  })

  it('offers no choice while the type is usable only through one profile, and picks it', () => {
    const { value } = renderSelect(
      { ...NEXTCLOUD, profileRequired: true, creatableWithOwnAddress: false },
      [RELEASED],
    )

    expect(value).toBe('profile-intern')
    // a single way needs no radio list: the row „Verbindung“ names the profile
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
    expect(screen.queryByText('Zugang')).not.toBeInTheDocument()
    expect(screen.queryByText(/nur über einen Zugang nutzbar/)).not.toBeInTheDocument()
  })

  it('says in one sentence that there is no profile when there is no way at all', () => {
    const { value } = renderSelect(
      { ...NEXTCLOUD, profileRequired: true, creatableWithOwnAddress: false },
      [],
    )

    expect(value).toBeNull()
    expect(screen.getByTestId('test-connection-none')).toHaveTextContent(
      'Für die Quellart „Nextcloud“ steht Ihnen kein Zugang zur Verfügung; die Systemverwaltung richtet Zugänge ein.',
    )
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  })

  it('never offers the own address where it is not part of the choice', () => {
    const second = { ...RELEASED, id: 'profile-extern', name: 'Nextcloud extern' }
    renderSelect(NEXTCLOUD, [RELEASED, second], { offerOwnAddress: false })

    expect(screen.queryByRole('radio', { name: /Eigene Adresse/ })).not.toBeInTheDocument()
    expect(screen.getAllByRole('radio')).toHaveLength(2)
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
