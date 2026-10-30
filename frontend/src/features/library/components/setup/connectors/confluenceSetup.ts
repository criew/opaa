import type { SourceConnectionTestRequest } from '@/shared/api/types'
import {
  MAX_CONFLUENCE_SPACES,
  confluenceCredentialsOf,
  confluenceEditionLabel,
  type ConfluenceSourceValues,
} from '../../../utils/confluenceSource'
import { connectionFields } from '../../sources/sourceConnection'
import type { SourceSetupSpec } from '../setupTypes'
import {
  addressIssue,
  credentialsKept,
  hostOf,
  issueList,
  joinSummary,
  listSummary,
} from './common'
import { ConfluenceConnectionFields, ConfluenceScopeFields } from './ConfluenceSetupFields'

export const confluenceSetup: SourceSetupSpec<ConfluenceSourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (values) =>
        values.sourceUrl.trim()
          ? joinSummary(
              hostOf(values.sourceUrl),
              values.edition && confluenceEditionLabel(values.edition),
            )
          : null,
      Fields: ConfluenceConnectionFields,
    },
    {
      id: 'scope',
      summary: (values) =>
        listSummary(
          values.allSpaces,
          ['Space', 'Spaces'],
          values.spaces.map((space) => space.name || space.key),
        ),
      Fields: ConfluenceScopeFields,
    },
  ],
  labels: {
    sourceUrl: 'Adresse',
    email: 'E-Mail-Adresse',
    token: 'API-Token',
    spaces: 'Spaces',
  },
  issues: (values, context) => {
    const { at, list } = issueList()
    const address = addressIssue(values.sourceUrl)
    if (address) at('sourceUrl', address)
    at('sourceUrl', 'Edition nicht erkannt, Adresse prüfen', values.edition === null)
    const kept = credentialsKept(context, values.sourceUrl)
    if (
      connectionFields(context).asksSecret &&
      (!kept || values.token !== '' || values.email !== '')
    ) {
      const cloud = values.edition !== 'DATA_CENTER'
      at('email', 'E-Mail-Adresse fehlt', cloud && values.email.trim() === '')
      at(
        'token',
        cloud ? 'API-Token fehlt' : 'Persönliches Zugriffstoken fehlt',
        values.token.trim() === '',
      )
    }
    at('spaces', 'Spaces fehlen', !values.allSpaces && values.spaces.length === 0)
    at(
      'spaces',
      `Höchstens ${MAX_CONFLUENCE_SPACES} Spaces`,
      !values.allSpaces && values.spaces.length > MAX_CONFLUENCE_SPACES,
    )
    return list
  },
  rowOf: (field) => (field === 'spaces' ? 'scope' : 'connection'),
  probe: (values, fields) =>
    fields.probeRequest<SourceConnectionTestRequest>({
      sourceType: 'CONFLUENCE',
      sourceUrl: values.sourceUrl.trim(),
      sourceCredentials: confluenceCredentialsOf(values),
      sourceSettings: { edition: values.edition },
    }),
}
