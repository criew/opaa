import type { SourceConnectionTestRequest } from '@/shared/api/types'
import {
  MAX_S3_SCOPES,
  s3CredentialsOf,
  s3SettingsOf,
  s3ValuesIssue,
  validateS3Scope,
  type S3SourceValues,
} from '../../../utils/s3Source'
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
  pairIssues,
} from './common'
import { S3ConnectionFields, S3ScopeFields } from './S3SetupFields'

const SCOPE_FIELDS = ['scopes', 'includePatterns', 'excludePatterns']

export const s3Setup: SourceSetupSpec<S3SourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (values, _context, check) =>
        values.sourceUrl.trim() ? joinSummary(hostOf(values.sourceUrl), checkWord(check)) : null,
      Fields: S3ConnectionFields,
    },
    {
      id: 'scope',
      summary: (values) => {
        const scopes = values.scopes.filter((scope) => scope.bucket.trim() !== '')
        if (scopes.length === 0) return null
        if (scopes.length === 1) {
          const [only] = scopes
          return only.prefix ? `${only.bucket}/${only.prefix}` : `Bucket ${only.bucket}`
        }
        return counted(scopes.length, ['Geltungsbereich', 'Geltungsbereiche'])
      },
      Fields: S3ScopeFields,
    },
  ],
  issues: (values, context) => {
    const { at, list } = issueList()
    const address = addressIssue(values.sourceUrl)
    if (address) at('sourceUrl', address.replace('Adresse', 'Endpoint'))
    at(
      'region',
      'Region: nur Buchstaben, Ziffern und Bindestriche',
      !/^[A-Za-z0-9-]{0,64}$/.test(values.region.trim()),
    )
    if (connectionFields(context).asksSecret) {
      pairIssues(
        at,
        ['accessKey', 'secretKey'],
        values.accessKey.trim(),
        values.secretKey.trim(),
        credentialsKept(context, values.sourceUrl),
        'Access Key',
        'Secret Key',
      )
      at(
        'sessionToken',
        'Session-Token ohne Doppelpunkt angeben',
        values.sessionToken.includes(':'),
      )
    }
    const scopes = values.scopes.filter((scope) => scope.bucket.trim() !== '')
    at('scopes', 'Mindestens einen Bucket wählen', scopes.length === 0)
    at('scopes', `Höchstens ${MAX_S3_SCOPES} Geltungsbereiche`, scopes.length > MAX_S3_SCOPES)
    const invalid = scopes.map(validateS3Scope).find(Boolean)
    if (invalid) at('scopes', invalid)
    // the pattern rules alone: everything else filled with valid stand-ins
    const patterns = s3ValuesIssue({
      ...values,
      sourceUrl: 'https://s3.example',
      region: '',
      accessKey: 'a',
      secretKey: 'b',
      sessionToken: '',
      scopes: [{ bucket: 'bucket', prefix: '' }],
    })
    if (patterns?.field) at(patterns.field, patterns.message)
    return list
  },
  rowOf: (field) => (SCOPE_FIELDS.includes(field) ? 'scope' : 'connection'),
  probe: (values, fields) =>
    fields.probeRequest<SourceConnectionTestRequest>({
      sourceType: 'S3',
      sourceUrl: values.sourceUrl.trim(),
      sourceCredentials: s3CredentialsOf(values),
      sourceSettings: s3SettingsOf(values),
    }),
}
