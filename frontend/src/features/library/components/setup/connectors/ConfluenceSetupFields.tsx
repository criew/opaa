import { useEffect, useState } from 'react'
import Stack from '@mui/material/Stack'
import { browseSource, testLibrarySource } from '../../../api/libraryApi'
import {
  confluenceCredentialsOf,
  confluenceEditionLabel,
  detectedConfluenceEdition,
  type ConfluenceSourceValues,
} from '../../../utils/confluenceSource'
import CredentialFields from '../CredentialFields'
import PickCombo, { type PickNode } from '../PickCombo'
import SetupTextField from '../SetupTextField'
import type { SetupFieldsProps } from '../setupTypes'
import { addressIssue, credentialsKept } from './common'

/**
 * Address and credentials of a Confluence instance. The edition is detected from the address
 * while it is typed - no step of its own - and decides whether an e-mail address is asked for.
 */
export function ConfluenceConnectionFields({
  values,
  onChange,
  context,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<ConfluenceSourceValues>) {
  const [detection, setDetection] = useState<{ url: string; text: string } | null>(null)
  const url = values.sourceUrl.trim()
  const detectable =
    addressIssue(url) === null && values.edition === null && context.mode === 'create'

  useEffect(() => {
    if (!detectable) return
    let cancelled = false
    const timer = setTimeout(() => {
      testLibrarySource(fields.probeRequest({ sourceType: 'CONFLUENCE', sourceUrl: url }))
        .then((result) => {
          if (cancelled) return
          const edition = detectedConfluenceEdition(result.details)
          if (edition) onChange({ edition })
          else setDetection({ url, text: result.message })
        })
        .catch((err: unknown) => {
          if (cancelled) return
          setDetection({ url, text: err instanceof Error ? err.message : 'Edition nicht erkannt.' })
        })
    }, 500)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
    // fields.probeRequest changes identity on every render; the probe scope is in the key below
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detectable, url, JSON.stringify(fields.probe)])

  const editionLine =
    values.edition !== null
      ? `Edition: ${confluenceEditionLabel(values.edition)}`
      : detection?.url === url
        ? detection.text
        : 'Die Edition wird aus der Adresse erkannt.'
  const cloud = values.edition !== 'DATA_CENTER'

  return (
    <Stack spacing={2}>
      <SetupTextField
        id={fieldId('sourceUrl')}
        label="Adresse"
        value={values.sourceUrl}
        onChange={(sourceUrl) =>
          onChange(
            context.mode === 'create'
              ? { sourceUrl, edition: null, credentialsVerified: false }
              : { sourceUrl },
          )
        }
        error={issueAt('sourceUrl')}
        hint={editionLine}
        placeholder="https://rheinfurt.atlassian.net/wiki"
        mono
      />
      {fields.asksSecret && (
        <CredentialFields
          stored={credentialsKept(context, values.sourceUrl)}
          account={
            cloud
              ? {
                  id: fieldId('email'),
                  label: 'Kennung (E-Mail-Adresse)',
                  value: values.email,
                  onChange: (email) => onChange({ email }),
                  error: issueAt('email'),
                  placeholder: 'dienstkonto@rheinfurt.example',
                }
              : undefined
          }
          secret={{
            id: fieldId('token'),
            label: 'Token',
            value: values.token,
            onChange: (token) => onChange({ token }),
            error: issueAt('token'),
            hint: cloud
              ? 'API-Token des Dienstkontos (id.atlassian.com › Sicherheit).'
              : 'Persönliches Zugriffstoken des Dienstkontos.',
          }}
        />
      )}
    </Stack>
  )
}

const spaceNode = (space: { key: string; name?: string | null }): PickNode => ({
  key: space.key,
  label: space.name || space.key,
  trail: [],
  secondary: space.key.startsWith('~') ? `Persönlicher Space · ${space.key}` : space.key,
})

export function ConfluenceScopeFields({
  values,
  onChange,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<ConfluenceSourceValues>) {
  const credentials = confluenceCredentialsOf(values)
  return (
    <PickCombo
      id={fieldId('spaces')}
      label="Spaces"
      hint="Nur Spaces, die das Dienstkonto lesen darf. Wer die Bibliothek liest, sieht alles aus diesen Spaces."
      error={issueAt('spaces')}
      noun={['Space', 'Spaces']}
      selected={values.spaces.map(spaceNode)}
      onChange={(nodes) => onChange({ spaces: nodes.map((n) => ({ key: n.key, name: n.label })) })}
      load={async () => {
        if (!values.edition)
          throw new Error('Zuerst unter „Verbindung“ eine gültige Adresse angeben.')
        const result = await browseSource(
          'CONFLUENCE',
          fields.probeRequest({
            sourceUrl: values.sourceUrl.trim(),
            sourceCredentials: credentials,
            query: { edition: values.edition },
          }),
        )
        return {
          complete: result.complete,
          message: result.message,
          nodes: result.entries.map(spaceNode),
        }
      }}
      max={500}
      loadKey={JSON.stringify({
        url: values.sourceUrl,
        credentials,
        edition: values.edition,
        probe: fields.probe,
      })}
      placeholder="Space suchen"
    />
  )
}
