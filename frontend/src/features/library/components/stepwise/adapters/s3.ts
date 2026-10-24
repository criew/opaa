import { S3ManualEntry } from './ScopeExtras'
import { browseSource } from '../../../api/libraryApi'
import {
  derivedS3Endpoint,
  isValidS3BucketName,
  normalizeS3Prefix,
  s3CredentialsOf,
  s3ProviderTemplate,
  S3_PROVIDER_LABELS,
  S3_PROVIDERS,
  type S3Provider,
  type S3ScopeInput,
  type S3SourceValues,
} from '../../../utils/s3Source'
import { checkByListing } from '../connectionCheck'
import type { FieldErrors, FieldSpec, ScopeNode, StepContext, StepwiseSource } from '../types'
import { addressError, asksCredentials, credentialsRow } from './common'

/** A bucket (`b:<bucket>`) or a prefix below it (`p:<bucket>:<prefix>/`). */
export function s3Node(bucket: string, prefix: string): ScopeNode {
  const segments = prefix.split('/').filter(Boolean)
  const ancestors = [
    `b:${bucket}`,
    ...segments.slice(0, -1).map((_, i) => `p:${bucket}:${segments.slice(0, i + 1).join('/')}/`),
  ]
  if (segments.length === 0) {
    return {
      id: `b:${bucket}`,
      name: bucket,
      ancestors: [],
      trail: [],
      expandable: true,
      selectable: true,
    }
  }
  return {
    id: `p:${bucket}:${segments.join('/')}/`,
    name: segments[segments.length - 1],
    ancestors,
    trail: [bucket, ...segments.slice(0, -1)],
    expandable: true,
    selectable: true,
  }
}

export function s3ScopeOfNode(node: ScopeNode): S3ScopeInput {
  if (node.id.startsWith('b:')) return { bucket: node.id.slice(2), prefix: '' }
  const rest = node.id.slice(2)
  const at = rest.indexOf(':')
  return { bucket: rest.slice(0, at), prefix: rest.slice(at + 1) }
}

function chosenScopes(values: S3SourceValues) {
  return values.scopes.filter((scope) => scope.bucket.trim() !== '')
}

function listingRequest(values: S3SourceValues, ctx: StepContext, query: Record<string, unknown>) {
  return ctx.fields.probeRequest({
    sourceUrl: values.sourceUrl.trim(),
    sourceCredentials: s3CredentialsOf(values),
    query: { region: values.region.trim() || undefined, pathStyle: values.pathStyle, ...query },
  })
}

export const s3Stepwise: StepwiseSource<S3SourceValues> = {
  connectionHeading: 'Verbindung zum Objektspeicher',
  fields: (values, ctx) => {
    const derived = derivedS3Endpoint(values.provider, values.region) !== null
    const specs: FieldSpec[] = [
      {
        key: 'provider',
        label: 'Anbieter',
        kind: 'choice',
        options: S3_PROVIDERS.map((p) => ({ value: p, label: S3_PROVIDER_LABELS[p] })),
      },
      {
        key: 'region',
        label: values.provider === 'HETZNER' ? 'Standort' : 'Region',
        kind: 'text',
        mono: true,
        optional:
          values.provider === 'MINIO' || values.provider === 'CEPH' || values.provider === 'OTHER',
        placeholder: values.provider === 'HETZNER' ? 'fsn1' : 'eu-central-1',
      },
      {
        key: 'sourceUrl',
        label: 'Adresse',
        kind: 'text',
        mono: true,
        placeholder: 'https://s3.example.de:9000',
        hint: derived ? 'Aus der Region abgeleitet.' : 'Endpoint des Objektspeichers, ohne Bucket.',
      },
    ]
    if (ctx.fields.asksSecret) {
      specs.push(
        { key: 'accessKey', label: 'Access Key', kind: 'text', mono: true, credential: true },
        { key: 'secretKey', label: 'Secret Key', kind: 'secret', credential: true },
        {
          key: 'sessionToken',
          label: 'Session-Token',
          kind: 'secret',
          optional: true,
          credential: true,
          hint: 'Nur für befristete Schlüssel.',
        },
      )
    }
    return specs
  },
  adjust: (patch, values) => {
    if (patch.provider && patch.provider !== values.provider) {
      return { ...patch, ...s3ProviderTemplate(patch.provider as S3Provider) }
    }
    if (patch.region !== undefined) {
      const endpoint = derivedS3Endpoint(values.provider, patch.region)
      if (endpoint !== null) return { ...patch, sourceUrl: endpoint }
    }
    return patch
  },
  connectionErrors: (values, ctx) => {
    const errors: FieldErrors = {}
    addressError(values.sourceUrl, errors)
    if (
      (values.provider === 'AWS' || values.provider === 'HETZNER') &&
      values.region.trim() === ''
    ) {
      errors.region =
        values.provider === 'HETZNER'
          ? 'Bitte den Standort eingeben.'
          : 'Bitte die Region eingeben.'
    }
    if (asksCredentials(ctx)) {
      if (values.accessKey.trim() === '') errors.accessKey = 'Bitte den Access Key eingeben.'
      if (values.secretKey.trim() === '') errors.secretKey = 'Bitte den Secret Key eingeben.'
    }
    return errors
  },
  stageFields: {
    address: ['sourceUrl', 'region'],
    signIn: ['accessKey', 'secretKey', 'sessionToken'],
  },
  check: (values, ctx) =>
    checkByListing(() => browseSource('S3', listingRequest(values, ctx, {})), 'Buckets'),
  connectionRows: (values, { credentialsStored }) => [
    { label: 'Anbieter', value: S3_PROVIDER_LABELS[values.provider] },
    { label: 'Adresse', value: values.sourceUrl, mono: true },
    ...(values.region.trim() ? [{ label: 'Region', value: values.region, mono: true }] : []),
    credentialsRow(credentialsStored, values.accessKey !== ''),
  ],
  scope: {
    heading: 'Welche Buckets und Ordner soll die Bibliothek lesen?',
    noun: ['Bucket', 'Buckets'],
    hierarchical: true,
    load: async (parent, values, ctx) => {
      const scope = parent ? s3ScopeOfNode(parent) : null
      const result = await browseSource(
        'S3',
        listingRequest(values, ctx, scope ? { bucket: scope.bucket, prefix: scope.prefix } : {}),
      )
      return {
        nodes: result.entries.map((entry) =>
          scope ? s3Node(scope.bucket, entry.key) : s3Node(entry.key, ''),
        ),
        complete: result.complete,
        message: result.message,
      }
    },
    selection: (values) =>
      chosenScopes(values).map((scope) =>
        s3Node(scope.bucket.trim(), normalizeS3Prefix(scope.prefix)),
      ),
    withSelection: (_values, nodes) => ({ scopes: nodes.map(s3ScopeOfNode) }),
    max: 50,
    Manual: S3ManualEntry,
    errors: (values) => {
      const scopes = chosenScopes(values)
      if (scopes.length === 0) return { scope: 'Bitte mindestens einen Bucket wählen.' }
      const invalid = scopes.find((scope) => !isValidS3BucketName(scope.bucket))
      if (invalid) return { scope: `„${invalid.bucket}“ ist kein gültiger Bucket-Name.` }
      return {}
    },
    failedEntries: (response) => {
      const scopes = (response.details?.scopes ?? []) as Array<{
        bucket: string
        prefix: string
        message?: string | null
      }>
      return Object.fromEntries(
        scopes
          .filter((scope) => scope.message)
          .map((scope) => [s3Node(scope.bucket, scope.prefix ?? '').id, scope.message as string]),
      )
    },
  },
  scopeRows: (values) => {
    const scopes = chosenScopes(values)
    return [
      {
        label: 'Bereiche',
        value: `${scopes.length} ${scopes.length === 1 ? 'Bereich' : 'Bereiche'}`,
        list: scopes.map((scope) => `${scope.bucket}/${normalizeS3Prefix(scope.prefix)}`),
        mono: true,
      },
    ]
  },
}
