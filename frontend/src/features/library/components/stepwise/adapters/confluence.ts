import { browseSource, testLibrarySource } from '../../../api/libraryApi'
import {
  confluenceCredentialsOf,
  confluenceEditionLabel,
  detectedConfluenceEdition,
  type ConfluenceEdition,
  type ConfluenceSourceValues,
} from '../../../utils/confluenceSource'
import { outcomeOfError, outcomeOfTest } from '../connectionCheck'
import type { FieldErrors, FieldSpec, ScopeNode, StepwiseSource } from '../types'
import { addressError, asksCredentials, credentialsRow } from './common'

/** The edition the address suggests until the instance tells: Atlassian hosts are Cloud. */
export function guessedEdition(values: ConfluenceSourceValues): ConfluenceEdition {
  if (values.edition) return values.edition
  return /\.atlassian\.net/i.test(values.sourceUrl) ? 'CLOUD' : 'DATA_CENTER'
}

function spaceNode(key: string, name: string | null | undefined): ScopeNode {
  return {
    id: key,
    name: name || key,
    ancestors: [],
    trail: [],
    expandable: false,
    selectable: true,
    meta: key.startsWith('~') ? 'Persönlicher Space' : key,
  }
}

export const confluenceStepwise: StepwiseSource<ConfluenceSourceValues> = {
  connectionHeading: 'Verbindung zu Confluence',
  fields: (values, ctx) => {
    const cloud = guessedEdition(values) === 'CLOUD'
    const specs: FieldSpec[] = [
      {
        key: 'sourceUrl',
        label: 'Adresse',
        kind: 'text',
        placeholder: 'https://firma.atlassian.net/wiki',
        mono: true,
        hint: values.edition
          ? `Erkannt: Confluence ${confluenceEditionLabel(values.edition)}`
          : (ctx.fields.addressHint ?? undefined),
      },
    ]
    if (ctx.fields.asksSecret) {
      if (cloud) {
        specs.push({
          key: 'email',
          label: 'E-Mail-Adresse',
          kind: 'text',
          inputType: 'email',
          credential: true,
          placeholder: 'dienstkonto@example.de',
        })
      }
      specs.push({
        key: 'token',
        label: cloud ? 'API-Token' : 'Persönliches Zugriffstoken',
        kind: 'secret',
        credential: true,
      })
    }
    return specs
  },
  adjust: (patch) =>
    'sourceUrl' in patch ? { ...patch, edition: null, credentialsVerified: false } : patch,
  connectionErrors: (values, ctx) => {
    const errors: FieldErrors = {}
    addressError(values.sourceUrl, errors)
    if (asksCredentials(ctx)) {
      if (guessedEdition(values) === 'CLOUD' && !/^[^@\s]+@[^@\s]+$/.test(values.email.trim())) {
        errors.email =
          values.email.trim() === ''
            ? 'Bitte die E-Mail-Adresse des Dienstkontos eingeben.'
            : 'Die E-Mail-Adresse hat die Form name@example.de.'
      }
      if (values.token.trim() === '') errors.token = 'Bitte das Token eingeben.'
    }
    return errors
  },
  stageFields: { address: ['sourceUrl'], signIn: ['email', 'token'] },
  check: async (values, ctx) => {
    const sourceUrl = values.sourceUrl.trim()
    try {
      const detection = await testLibrarySource(
        ctx.fields.probeRequest({ sourceType: 'CONFLUENCE', sourceUrl }),
      )
      const edition = detectedConfluenceEdition(detection.details) ?? values.edition
      if (!edition) {
        return {
          stages: { address: 'failed', signIn: 'open', rights: 'open' },
          message: detection.message,
          ok: false,
        }
      }
      const signed = await testLibrarySource(
        ctx.fields.probeRequest({
          sourceType: 'CONFLUENCE',
          sourceUrl,
          sourceCredentials: confluenceCredentialsOf({ ...values, edition }),
          sourceSettings: { edition },
        }),
      )
      const outcome = outcomeOfTest(signed, { signsIn: true, rightsLater: true })
      const verified = signed.credentialsVerified === true
      return {
        ...(verified
          ? outcome
          : { ...outcome, ok: false, stages: { address: 'ok', signIn: 'failed', rights: 'open' } }),
        patch: { edition, credentialsVerified: verified },
      }
    } catch (err) {
      return outcomeOfError(err, true)
    }
  },
  connectionRows: (values, { credentialsStored }) => [
    { label: 'Adresse', value: values.sourceUrl, mono: true },
    {
      label: 'Edition',
      value: values.edition ? confluenceEditionLabel(values.edition) : 'Noch nicht erkannt',
    },
    credentialsRow(credentialsStored, values.token !== ''),
  ],
  scope: {
    heading: 'Welche Spaces soll die Bibliothek lesen?',
    noun: ['Space', 'Spaces'],
    hierarchical: false,
    load: async (_parent, values, ctx) => {
      const result = await browseSource(
        'CONFLUENCE',
        ctx.fields.probeRequest({
          sourceUrl: values.sourceUrl.trim(),
          sourceCredentials: confluenceCredentialsOf(values),
          query: { edition: values.edition },
        }),
      )
      return {
        nodes: result.entries.map((entry) => spaceNode(entry.key, entry.name)),
        complete: result.complete,
        message: result.message,
      }
    },
    selection: (values) => values.spaces.map((space) => spaceNode(space.key, space.name)),
    withSelection: (_values, nodes) => ({
      spaces: nodes.map((node) => ({ key: node.id, name: node.name })),
    }),
    max: 500,
    errors: (values) =>
      values.spaces.length === 0 ? { scope: 'Bitte mindestens einen Space wählen.' } : {},
    failedEntries: (response) => {
      const unreadable = (response.details?.unreadableSpaces ?? []) as string[]
      return Object.fromEntries(
        unreadable.map((key) => [key, 'kein Leserecht für das Dienstkonto']),
      )
    },
  },
  scopeRows: (values) => [
    {
      label: 'Spaces',
      value: `${values.spaces.length} ${values.spaces.length === 1 ? 'Space' : 'Spaces'}`,
      list: values.spaces.map((space) => space.name || space.key),
    },
  ],
}
