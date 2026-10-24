import { useCallback, useState } from 'react'
import Box from '@mui/material/Box'
import { browseSource, testLibrarySource } from '../../../api/libraryApi'
import { sameLibrarySourceOrigin } from '../../../utils/librarySourceConfig'
import {
  MAX_CONFLUENCE_SPACES,
  confluenceCredentialsOf,
  confluenceEditionLabel,
  detectedConfluenceEdition,
  type ConfluenceSourceValues,
} from '../../../utils/confluenceSource'
import { connectionFields } from '../../sources/sourceConnection'
import type { SourceFormProps } from '../../sources/types'
import ConnectionStage from '../ConnectionStage'
import {
  outcomeOf,
  outcomeOfError,
  type ConnectionOutcome,
  type ConnectionStageKey,
} from '../connectionOutcome'
import SecretField from '../SecretField'
import SourceField from '../SourceField'
import { pickedOfSpaces } from '../pickedOf'
import SplitPicker from '../SplitPicker'
import type { PickerLevel } from '../pickerTypes'
import { SelectionPending } from './AccountFolderForm'

/**
 * The source form of a Confluence library. „Verbindung testen" first recognises the edition behind
 * the address, then signs in; the e-mail address is asked only where the edition needs it
 * (Cloud). The spaces the account can read follow in the split selection.
 */
export default function ConfluenceForm({
  values,
  onChange,
  context,
}: SourceFormProps<ConfluenceSourceValues>) {
  const id = `${context.idPrefix}-confluence`
  const connection = connectionFields(context)
  const [outcome, setOutcome] = useState<ConnectionOutcome | null>(null)
  const stored =
    context.mode === 'edit' &&
    context.credentialsStored &&
    sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl)
  const token = JSON.stringify({
    probe: connection.probe,
    url: values.sourceUrl,
    email: values.email,
    secret: values.token,
  })

  const run = async (): Promise<ConnectionOutcome> => {
    const stages: ConnectionStageKey[] = ['address', 'auth', 'rights']
    try {
      let edition = values.edition
      if (!edition) {
        const detection = await testLibrarySource(
          connection.probeRequest({ sourceType: 'CONFLUENCE', sourceUrl: values.sourceUrl.trim() }),
        )
        edition = detectedConfluenceEdition(detection.details)
        if (!edition) return { ok: false, failedStage: 'address', message: detection.message }
        onChange({ edition })
      }
      const response = await testLibrarySource(
        connection.probeRequest({
          sourceType: 'CONFLUENCE',
          sourceUrl: values.sourceUrl.trim(),
          sourceSettings: { edition },
          sourceCredentials: confluenceCredentialsOf({ ...values, edition }),
        }),
      )
      const verified = response.credentialsVerified === true
      onChange({ edition, credentialsVerified: verified })
      if (verified) return { ok: true, message: response.message }
      return outcomeOf({ ...response, reachable: false }, stages)
    } catch (err) {
      return outcomeOfError(err, stages)
    }
  }

  const load = useCallback(
    async (): Promise<PickerLevel> => {
      const response = await browseSource(
        'CONFLUENCE',
        connection.probeRequest({
          sourceUrl: values.sourceUrl.trim(),
          query: { edition: values.edition },
          sourceCredentials: confluenceCredentialsOf(values),
        }),
      )
      return {
        complete: response.complete,
        message: response.message ?? null,
        entries: response.entries.map((entry) => ({
          key: entry.key,
          name: entry.name ?? entry.key,
          hint: entry.key.startsWith('~') ? 'Persönlicher Space' : null,
          selectable: true,
          hasChildren: false,
        })),
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [token, values.edition],
  )

  const isCloud = values.edition !== 'DATA_CENTER'
  const complete = values.sourceUrl.trim() !== '' && (values.token !== '' || stored)

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <ConnectionStage
        idPrefix={id}
        token={token}
        stages={['address', 'auth', 'rights']}
        run={run}
        summary={[
          values.sourceUrl.trim(),
          values.edition ? confluenceEditionLabel(values.edition) : '',
          values.email.trim() || (stored ? 'gespeichertes Konto' : ''),
        ]}
        fields={['sourceUrl', 'email', 'token']}
        autoTest={complete && (context.mode === 'edit' || values.credentialsVerified)}
        onOutcome={setOutcome}
      >
        <SourceField
          id={`${id}-url`}
          field="sourceUrl"
          label="Adresse"
          value={values.sourceUrl}
          onChange={(next) =>
            onChange({ sourceUrl: next, edition: null, credentialsVerified: false })
          }
          placeholder="https://firma.atlassian.net/wiki"
          hint={connection.addressHint ?? undefined}
          mono
        />
        {isCloud && (
          <SourceField
            id={`${id}-email`}
            field="email"
            label="Konto"
            value={values.email}
            onChange={(next) => onChange({ email: next, credentialsVerified: false })}
            placeholder="name@example.org"
            hint={
              values.edition === 'CLOUD'
                ? undefined
                : 'E-Mail-Adresse; nur Confluence Cloud braucht sie.'
            }
          />
        )}
        <SecretField
          id={`${id}-token`}
          field="token"
          label={values.edition === 'DATA_CENTER' ? 'Persönliches Token' : 'API-Token'}
          value={values.token}
          onChange={(next) => onChange({ token: next, credentialsVerified: false })}
          stored={stored}
        />
      </ConnectionStage>

      {outcome?.ok ? (
        <SplitPicker
          key={token}
          idPrefix={`${id}-spaces`}
          title="Auswahl"
          rootLabel="Confluence"
          noun={{ one: 'Space', many: 'Spaces' }}
          load={load}
          selected={pickedOfSpaces(values.spaces)}
          onChange={(next) =>
            onChange({ spaces: next.map((item) => ({ key: item.key, name: item.name })) })
          }
          max={MAX_CONFLUENCE_SPACES}
          emptySelection="Noch kein Space gewählt."
          issueFields={['spaces']}
        />
      ) : (
        <SelectionPending issueFields={['spaces']} />
      )}
    </Box>
  )
}
