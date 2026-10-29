import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../../../test/test-utils'
import { server } from '../../../../../test/server'
import { EMPTY_DROPBOX_VALUES, type DropboxSourceValues } from '../../../utils/dropboxSource'
import { connectionFields, type SourceConnection } from '../../sources/sourceConnection'
import type { SourceFormContext } from '../../sources/types'
import { DropboxConnectionFields, DropboxScopeFields } from './DropboxSetupFields'

const MEMBER_APP: SourceConnection = {
  profileId: 'profile-dropbox',
  name: 'Dropbox Bauamt',
  serverUrl: 'https://api.dropboxapi.com',
  authMethod: 'OAUTH',
  defaults: {},
}

const TEAM_APP: SourceConnection = { ...MEMBER_APP, defaults: { teamApp: true } }

function contextOn(connection: SourceConnection): SourceFormContext {
  return {
    mode: 'create',
    sourceType: 'DROPBOX',
    idPrefix: 'test',
    credentialsStored: false,
    connection,
  }
}

function renderFields(connection: SourceConnection) {
  const context = contextOn(connection)
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
    renderFields(TEAM_APP)

    expect(screen.queryByLabelText('Teambereich lesen')).not.toBeInTheDocument()
    expect(screen.getByText(/liest Team-Ordner des Teams/)).toBeInTheDocument()
  })
})

describe('DropboxScopeFields', () => {
  it('offers the team folders of a team app and chooses them by namespace id', async () => {
    let asked: { query?: Record<string, unknown> } | null = null
    server.use(
      http.post('/api/v1/source-types/DROPBOX/browse', async ({ request }) => {
        asked = (await request.json()) as { query?: Record<string, unknown> }
        return HttpResponse.json({
          complete: true,
          entries: [
            { key: '100', name: 'Bauamt' },
            { key: '200', name: 'Kämmerei' },
          ],
        })
      }),
    )
    const onChange = vi.fn<(patch: Partial<DropboxSourceValues>) => void>()
    const context = contextOn(TEAM_APP)
    renderWithProviders(
      <DropboxScopeFields
        values={EMPTY_DROPBOX_VALUES}
        onChange={onChange}
        context={context}
        fields={connectionFields(context)}
        issueAt={() => null}
        fieldId={(field) => `test-${field}`}
      />,
    )

    await userEvent.click(await screen.findByRole('checkbox', { name: 'Kämmerei' }))

    expect(onChange).toHaveBeenLastCalledWith({ teamFolders: [{ id: '200', name: 'Kämmerei' }] })
    expect(asked).toMatchObject({ query: {} })
    expect(screen.queryByText(/Ordner auswählen/)).not.toBeInTheDocument()
  })
})
