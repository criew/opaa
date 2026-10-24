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
  checkWord,
  counted,
  credentialsKept,
  hostOf,
  issueList,
  joinSummary,
} from './common'
import { ConfluenceConnectionFields, ConfluenceScopeFields } from './ConfluenceSetupFields'

export const confluenceSetup: SourceSetupSpec<ConfluenceSourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (values, _context, check) =>
        values.sourceUrl.trim()
          ? joinSummary(
              hostOf(values.sourceUrl),
              values.edition && confluenceEditionLabel(values.edition),
              checkWord(check),
            )
          : null,
      Fields: ConfluenceConnectionFields,
    },
    {
      id: 'scope',
      summary: (values) =>
        values.spaces.length > 0 ? counted(values.spaces.length, ['Space', 'Spaces']) : null,
      Fields: ConfluenceScopeFields,
    },
  ],
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
      at('token', 'Token fehlt', values.token.trim() === '')
    }
    at('spaces', 'Mindestens einen Space wählen', values.spaces.length === 0)
    at(
      'spaces',
      `Höchstens ${MAX_CONFLUENCE_SPACES} Spaces`,
      values.spaces.length > MAX_CONFLUENCE_SPACES,
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
