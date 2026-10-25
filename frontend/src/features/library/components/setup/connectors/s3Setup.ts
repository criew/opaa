import type { SourceConnectionTestRequest } from '@/shared/api/types'
import {
  MAX_S3_SCOPES,
  s3CredentialsOf,
  s3ScopeKey,
  s3ScopesOverlap,
  s3SettingsOf,
  s3ValuesIssue,
  validateS3Scope,
  type S3SourceValues,
} from '../../../utils/s3Source'
import { connectionFields } from '../../sources/sourceConnection'
import { displayPath } from '../paths'
import type { SourceSetupSpec } from '../setupTypes'
import {
  addressIssue,
  credentialsKept,
  hostOf,
  issueList,
  joinSummary,
  listSummary,
  pairIssues,
} from './common'
import { S3ConnectionFields, S3ScopeFields } from './S3SetupFields'

const SCOPE_FIELDS = ['scopes', 'includePatterns', 'excludePatterns']

export const s3Setup: SourceSetupSpec<S3SourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (values) => (values.sourceUrl.trim() ? joinSummary(hostOf(values.sourceUrl)) : null),
      Fields: S3ConnectionFields,
    },
    {
      id: 'scope',
      summary: (values) => {
        const list = listSummary(
          false,
          ['Bucket oder Präfix', 'Buckets und Präfixe'],
          values.scopes
            .filter((scope) => scope.bucket.trim() !== '')
            .map((scope) => displayPath(`${scope.bucket}/${scope.prefix}`)),
        )
        // the patterns are entries of their own: what is kept („nur …“), what is left out
        const lines = (text: string) =>
          text
            .split('\n')
            .map((line) => line.trim())
            .filter(Boolean)
        const patterns = [
          ...lines(values.includePatterns).map((pattern) => `nur ${pattern}`),
          ...lines(values.excludePatterns).map((pattern) => `außer ${pattern}`),
        ]
        if (!list || patterns.length === 0) return list
        return typeof list === 'string'
          ? { lead: list, items: patterns }
          : { ...list, items: [...list.items, ...patterns] }
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
    at('scopes', 'Buckets und Präfixe fehlen', scopes.length === 0)
    at('scopes', `Höchstens ${MAX_S3_SCOPES} Buckets und Präfixe`, scopes.length > MAX_S3_SCOPES)
    const invalid = scopes.map(validateS3Scope).find(Boolean)
    if (invalid) at('scopes', invalid)
    const overlap = scopes.findIndex((scope, i) =>
      scopes.slice(0, i).some((earlier) => s3ScopesOverlap(scope, earlier)),
    )
    if (overlap > 0) {
      const earlier = scopes.find((scope) => s3ScopesOverlap(scope, scopes[overlap]))!
      at(
        'scopes',
        `„${s3ScopeKey(earlier)}“ und „${s3ScopeKey(scopes[overlap])}“ überschneiden sich`,
      )
    }
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
  labels: {
    sourceUrl: 'Endpoint',
    region: 'Region',
    accessKey: 'Access Key',
    secretKey: 'Secret Key',
    sessionToken: 'Session-Token',
    scopes: 'Buckets und Präfixe',
    includePatterns: 'Einschlussmuster',
    excludePatterns: 'Ausschlussmuster',
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
