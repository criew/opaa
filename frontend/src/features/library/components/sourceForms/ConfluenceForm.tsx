import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import { browseSource, testLibrarySource } from '../../api/libraryApi'
import {
  confluenceCredentialsOf,
  confluenceEditionLabel,
  detectedConfluenceEdition,
  type ConfluenceEdition,
  type ConfluenceSourceValues,
} from '../../utils/confluenceSource'
import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import { connectionFields } from '../sources/sourceConnection'
import { fieldDomId, useSourceIssue } from '../sources/sourceIssueContext'
import type { SourceFormProps } from '../sources/types'
import { FieldGroup, SecretField, SourceTextField } from '../sourceKit/fields'
import SelectionSummary from '../sourceKit/SelectionSummary'
import { spaceItem, spaceOfItem, spaceSelectionSource } from '../sourceKit/selectionSources'

/**
 * The Confluence form (Variante E): the edition is detected from the address when the field is
 * left - it decides whether an e-mail address belongs to the sign-in -, the spaces are chosen in
 * the shared selection dialog. A successful listing counts as verified credentials.
 */
export default function ConfluenceForm({
  values,
  onChange,
  context,
}: SourceFormProps<ConfluenceSourceValues>) {
  const fields = connectionFields(context)
  const issueFor = useSourceIssue()
  const prefix = context.idPrefix
  const editionFixed =
    (context.mode === 'edit' && values.edition !== null) || fields.isFixed('edition')
  const stored =
    context.mode === 'edit' &&
    context.credentialsStored &&
    sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl)
  // the edition belongs to the address it was detected for; a late answer for another is dropped
  const [detected, setDetected] = useState<{ url: string; edition: ConfluenceEdition } | null>(
    null,
  )
  const url = values.sourceUrl.trim()
  useEffect(() => {
    if (detected && detected.url === url && values.edition === null) {
      onChange({ edition: detected.edition, credentialsVerified: false })
    }
  }, [detected, url, values.edition, onChange])

  async function detect(): Promise<ConfluenceEdition | null> {
    if (editionFixed) return values.edition
    if (!/^https?:\/\//i.test(url)) return null
    try {
      const result = await testLibrarySource(
        fields.probeRequest({ sourceType: 'CONFLUENCE', sourceUrl: url }),
      )
      const edition = detectedConfluenceEdition(result.details)
      if (edition) setDetected({ url, edition })
      return edition
    } catch {
      return null
    }
  }

  const source = spaceSelectionSource(async () => {
    const edition = values.edition ?? (await detect())
    if (!edition) throw new Error('Unter dieser Adresse antwortet keine Confluence-Instanz.')
    const result = await browseSource(
      'CONFLUENCE',
      fields.probeRequest({
        sourceUrl: values.sourceUrl.trim(),
        sourceCredentials: confluenceCredentialsOf({ ...values, edition }),
        query: { edition },
      }),
    )
    if (!values.credentialsVerified) onChange({ credentialsVerified: true })
    return result
  })

  const notReady =
    values.sourceUrl.trim() === ''
      ? 'Bitte zuerst die Adresse eingeben.'
      : fields.asksSecret && !stored && values.token.trim() === ''
        ? 'Bitte zuerst Kennung und Kennwort eingeben.'
        : null

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <FieldGroup title="Verbindung">
        <SourceTextField
          idPrefix={prefix}
          field="sourceUrl"
          label="Adresse"
          value={values.sourceUrl}
          onChange={(sourceUrl) =>
            onChange({
              sourceUrl,
              edition: editionFixed ? values.edition : null,
              credentialsVerified: false,
            })
          }
          onBlur={() => {
            if (values.edition === null) void detect()
          }}
          placeholder="https://example.atlassian.net/wiki"
          hint={
            fields.addressHint ??
            (values.edition
              ? `Confluence ${confluenceEditionLabel(values.edition)}`
              : 'Die Edition wird aus der Adresse erkannt.')
          }
          mono
          maxLength={2000}
        />
      </FieldGroup>
      {fields.asksSecret && (
        <FieldGroup title="Anmeldung">
          {values.edition !== 'DATA_CENTER' && (
            <SourceTextField
              idPrefix={prefix}
              field="email"
              label="Kennung"
              value={values.email}
              onChange={(email) => onChange({ email, credentialsVerified: false })}
              placeholder="dienstkonto@example.org"
              hint="E-Mail-Adresse des Dienstkontos"
              maxLength={255}
            />
          )}
          <SecretField
            idPrefix={prefix}
            field="token"
            label="Kennwort"
            value={values.token}
            stored={stored}
            onChange={(token) => onChange({ token, credentialsVerified: false })}
            hint={values.edition === 'DATA_CENTER' ? 'Persönliches Zugriffstoken' : 'API-Token'}
            maxLength={2000}
          />
        </FieldGroup>
      )}
      <FieldGroup title="Umfang">
        <SelectionSummary
          id={fieldDomId(prefix, 'selection')}
          label="Spaces"
          source={source}
          value={values.spaces.map(spaceItem)}
          onChange={(items) => onChange({ spaces: items.map(spaceOfItem) })}
          emptyText="Noch keine Spaces ausgewählt."
          error={issueFor('selection')}
          notReady={notReady}
        />
      </FieldGroup>
    </Box>
  )
}
