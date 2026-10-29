import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../../../../test/test-utils'
import { EMPTY_DROPBOX_VALUES } from '../../../utils/dropboxSource'
import { connectionFields, type SourceConnection } from '../../sources/sourceConnection'
import type { SourceFormContext } from '../../sources/types'
import { DropboxConnectionFields } from './DropboxSetupFields'

const MEMBER_APP: SourceConnection = {
  profileId: 'profile-dropbox',
  name: 'Dropbox Bauamt',
  serverUrl: 'https://api.dropboxapi.com',
  authMethod: 'OAUTH',
  defaults: {},
}

function renderFields(connection: SourceConnection) {
  const context: SourceFormContext = {
    mode: 'create',
    sourceType: 'DROPBOX',
    idPrefix: 'test',
    credentialsStored: false,
    connection,
  }
  renderWithProviders(
    <DropboxConnectionFields
      values={EMPTY_DROPBOX_VALUES}
      onChange={() => {}}
      context={context}
      fields={connectionFields(context)}
      issueAt={() => null}
      fieldId={(field) => `test-${field}`}
    />,
  )
}

describe('DropboxConnectionFields', () => {
  it('offers the team space of the connected account under a member app', () => {
    renderFields(MEMBER_APP)

    expect(screen.getByLabelText('Teambereich lesen')).toBeInTheDocument()
  })

  it('names the team app and offers no team space switch under it', () => {
    renderFields({ ...MEMBER_APP, defaults: { teamApp: true } })

    expect(screen.queryByLabelText('Teambereich lesen')).not.toBeInTheDocument()
    expect(screen.getByText(/liest Team-Ordner des Teams/)).toBeInTheDocument()
  })
})
