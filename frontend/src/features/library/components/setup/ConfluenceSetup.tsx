import { useState } from 'react'
import Typography from '@mui/material/Typography'
import { testLibrarySource } from '../../api/libraryApi'
import {
  confluenceCredentialsOf,
  confluenceEditionLabel,
  detectedConfluenceEdition,
  type ConfluenceEdition,
  type ConfluenceSourceValues,
} from '../../utils/confluenceSource'
import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import { connectionFields } from '../sources/sourceConnection'
import type { SourceFormProps } from '../sources/types'
import ConnectionCheck from './ConnectionCheck'
import FlatScope from './FlatScope'
import ScopeLoader from './ScopeLoader'
import SetupField, { ScopeField, StoredCredentials } from './SetupField'
import SourceSetupLayout from './SourceSetupLayout'
import { browseWith, testRequestOf, tokenOf } from './probe'
import { addressIssue } from './validation'

/**
 * Confluence on the shared frame. The edition is detected from the address on leaving the field
 * (and again by the check when it is still unknown); the spaces load once the check passed.
 */
export default function ConfluenceSetup({
  values,
  onChange,
  context,
}: SourceFormProps<ConfluenceSourceValues>) {
  const fields = connectionFields(context)
  const [detecting, setDetecting] = useState(false)
  const [detectNote, setDetectNote] = useState<{ url: string; text: string } | null>(null)
  const kept =
    context.mode === 'edit' &&
    context.credentialsStored &&
    sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl)
  const credentials = confluenceCredentialsOf(values)
  const connectionToken = tokenOf(values.sourceUrl.trim(), values.edition, credentials ?? null)

  async function detect(): Promise<ConfluenceEdition | null> {
    const url = values.sourceUrl.trim()
    if (addressIssue('sourceUrl', url)) return null
    setDetecting(true)
    try {
      const result = await testLibrarySource(
        fields.probeRequest({ sourceType: 'CONFLUENCE' as const, sourceUrl: url }),
      )
      const edition = detectedConfluenceEdition(result.details)
      onChange({ edition, credentialsVerified: false })
      setDetectNote(edition ? null : { url, text: result.message })
      return edition
    } catch (err) {
      setDetectNote({
        url,
        text: err instanceof Error ? err.message : 'Die Edition ließ sich nicht erkennen.',
      })
      return null
    } finally {
      setDetecting(false)
    }
  }

  const note = detectNote?.url === values.sourceUrl.trim() ? detectNote.text : null
  const cloud = values.edition !== 'DATA_CENTER'
  return (
    <SourceSetupLayout
      connection={
        <>
          <SetupField
            field="sourceUrl"
            label="Adresse der Confluence-Instanz"
            value={values.sourceUrl}
            onChange={(sourceUrl) =>
              onChange({ sourceUrl, edition: null, credentialsVerified: false })
            }
            onBlur={() => {
              if (!values.edition) void detect()
            }}
            placeholder="https://firma.atlassian.net/wiki"
            hint={fields.addressHint ?? undefined}
            mono
          />
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }} role="status">
            {detecting
              ? 'Edition wird erkannt …'
              : values.edition
                ? `Edition: Confluence ${confluenceEditionLabel(values.edition)}`
                : (note ?? 'Die Edition wird beim Verlassen des Felds erkannt.')}
          </Typography>
        </>
      }
      credentials={
        fields.asksSecret ? (
          <StoredCredentials
            stored={kept}
            label="Zugangsdaten"
            onKeep={() => onChange({ email: '', token: '' })}
          >
            {cloud && (
              <SetupField
                field="email"
                label="E-Mail-Adresse"
                type="email"
                value={values.email}
                onChange={(email) => onChange({ email, credentialsVerified: false })}
                hint={values.edition ? undefined : 'Nur bei Confluence Cloud.'}
                width="medium"
              />
            )}
            <SetupField
              field="token"
              label="Token"
              type="password"
              value={values.token}
              onChange={(token) => onChange({ token, credentialsVerified: false })}
              hint={
                values.edition === 'DATA_CENTER'
                  ? 'Persönliches Zugriffstoken.'
                  : 'API-Token des Dienstkontos.'
              }
              width="medium"
            />
          </StoredCredentials>
        ) : undefined
      }
      check={
        <ConnectionCheck
          stages={['address', 'signIn', 'access']}
          token={connectionToken}
          missingHint="Bitte zuerst Adresse und Token eingeben."
          request={async () => {
            if (addressIssue('sourceUrl', values.sourceUrl)) return null
            if (!credentials && !kept && fields.asksSecret) return null
            const edition = values.edition ?? (await detect())
            if (!edition) return null
            return testRequestOf(context, {
              sourceUrl: values.sourceUrl.trim(),
              sourceCredentials: confluenceCredentialsOf({ ...values, edition }),
              sourceSettings: { edition },
            })
          }}
          onResponse={(response) =>
            onChange({ credentialsVerified: Boolean(response?.credentialsVerified) })
          }
        />
      }
      scope={
        <ScopeField field="spaces">
          <ScopeLoader
            loadLabel="Spaces laden"
            hint="Nach einem erfolgreichen Verbindungstest erscheinen hier die lesbaren Spaces."
            token={connectionToken}
            autoLoad={values.credentialsVerified}
          >
            {() => (
              <FlatScope
                load={() =>
                  browseWith(context, {
                    sourceUrl: values.sourceUrl.trim(),
                    sourceCredentials: credentials,
                    query: { edition: values.edition },
                  })
                }
                toItem={(entry) => ({
                  id: entry.key,
                  label: entry.name ?? entry.key,
                  secondary: entry.key.startsWith('~') ? 'persönlich' : entry.key,
                })}
                selected={values.spaces.map((space) => ({
                  id: space.key,
                  label: space.name ?? space.key,
                }))}
                onChange={(next) =>
                  onChange({ spaces: next.map((item) => ({ key: item.id, name: item.label })) })
                }
                label="Spaces"
                noun={{ one: 'Space', many: 'Spaces' }}
                emptyText="Mit diesen Zugangsdaten ist kein Space lesbar."
              />
            )}
          </ScopeLoader>
        </ScopeField>
      }
    />
  )
}
