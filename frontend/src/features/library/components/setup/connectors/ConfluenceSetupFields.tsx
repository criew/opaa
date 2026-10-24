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
import { plainMessage } from '../../sourceKit/plainMessage'
import { spaceItem, spaceOfItem, spaceSelectionSource } from '../../sourceKit/selectionSources'
import ScopePicker from '../ScopePicker'
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
        ? plainMessage(detection.text)
        : undefined
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
                  label: 'E-Mail-Adresse',
                  value: values.email,
                  onChange: (email) => onChange({ email }),
                  error: issueAt('email'),
                  placeholder: 'dienstkonto@rheinfurt.example',
                }
              : undefined
          }
          secret={{
            id: fieldId('token'),
            label: cloud ? 'API-Token' : 'Persönliches Zugriffstoken',
            value: values.token,
            onChange: (token) => onChange({ token }),
            error: issueAt('token'),
            hint: cloud
              ? 'In Atlassian unter Konto › Sicherheit › API-Token.'
              : 'In Confluence unter Profil › Persönliche Zugriffstoken.',
          }}
        />
      )}
    </Stack>
  )
}

export function ConfluenceScopeFields({
  values,
  onChange,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<ConfluenceSourceValues>) {
  const credentials = confluenceCredentialsOf(values)
  const source = spaceSelectionSource(async () => {
    if (!values.edition) throw new Error('Zuerst unter „Verbindung“ eine gültige Adresse angeben.')
    return browseSource(
      'CONFLUENCE',
      fields.probeRequest({
        sourceUrl: values.sourceUrl.trim(),
        sourceCredentials: credentials,
        query: { edition: values.edition },
      }),
    )
  })
  return (
    <ScopePicker
      id={fieldId('spaces')}
      source={source}
      value={values.spaces.map(spaceItem)}
      all={{ active: values.everything, onChange: (everything) => onChange({ everything }) }}
      onChange={(items) => onChange({ spaces: items.map(spaceOfItem) })}
      error={issueAt('spaces')}
      loadKey={JSON.stringify({
        url: values.sourceUrl,
        credentials,
        edition: values.edition,
        probe: fields.probe,
      })}
    />
  )
}
