import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../../../../test/test-utils'
import { EMPTY_GENERIC_SOURCE_VALUES } from '../../../utils/librarySourceConfig'
import { connectionFields, type SourceConnection } from '../../sources/sourceConnection'
import type { SourceFormContext } from '../../sources/types'
import { UrlConnectionFields } from './GenericSetupFields'

const RSS_PROFILE: SourceConnection = {
  profileId: 'profile-rss',
  name: 'Pressestelle',
  serverUrl: 'https://presse.rheinfurt.example',
  authMethod: 'NONE',
  defaults: {},
}

function renderFields(connection?: SourceConnection) {
  const context: SourceFormContext = {
    mode: 'create',
    sourceType: 'RSS_FEED',
    idPrefix: 'test',
    credentialsStored: false,
    connection,
  }
  renderWithProviders(
    <UrlConnectionFields
      values={EMPTY_GENERIC_SOURCE_VALUES}
      onChange={() => {}}
      context={context}
      fields={connectionFields(context)}
      issueAt={() => null}
      fieldId={(field) => `test-${field}`}
    />,
  )
}

describe('UrlConnectionFields', () => {
  it('asks for credentials of a library with its own address', () => {
    renderFields()

    expect(screen.getByLabelText('Zugangsdaten (optional)')).toBeInTheDocument()
  })

  // regression guard for #3136: a profile without sign-in takes no credentials of the library
  it('asks for no credentials on a profile without sign-in and names its server address', () => {
    renderFields(RSS_PROFILE)

    expect(screen.queryByLabelText('Zugangsdaten (optional)')).not.toBeInTheDocument()
    expect(
      screen.getByText(
        'Liegt unter der Server-Adresse des Zugangs „Pressestelle“: https://presse.rheinfurt.example',
      ),
    ).toBeInTheDocument()
  })
})
