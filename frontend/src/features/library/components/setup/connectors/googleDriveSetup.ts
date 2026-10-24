import type { SourceConnectionTestRequest } from '@/shared/api/types'
import {
  GOOGLE_DRIVE_API,
  MAX_GOOGLE_DRIVE_SCOPES,
  googleDriveSettingsOf,
  storedKeyServes,
  type GoogleDriveSourceValues,
} from '../../../utils/googleDriveSource'
import { connectionFields } from '../../sources/sourceConnection'
import type { SourceSetupSpec } from '../setupTypes'
import { checkWord, counted, issueList, joinSummary } from './common'
import { GoogleDriveConnectionFields, GoogleDriveScopeFields } from './GoogleDriveSetupFields'

export const googleDriveSetup: SourceSetupSpec<GoogleDriveSourceValues> = {
  sections: [
    {
      id: 'connection',
      summary: (values, context, check) => {
        const account =
          values.keyAccount ||
          (storedKeyServes(values, context.credentialsStored) ? 'Schlüssel gesetzt' : '')
        return joinSummary(
          account,
          values.subject.trim() && `als ${values.subject.trim()}`,
          account && checkWord(check),
        )
      },
      Fields: GoogleDriveConnectionFields,
    },
    {
      id: 'scope',
      summary: (values) =>
        values.scopes.length > 0 ? counted(values.scopes.length, ['Bereich', 'Bereiche']) : null,
      Fields: GoogleDriveScopeFields,
    },
  ],
  issues: (values, context) => {
    const { at, list } = issueList()
    if (
      connectionFields(context).asksSecret &&
      values.keyFile === '' &&
      !storedKeyServes(values, context.credentialsStored)
    ) {
      at(
        'keyFile',
        context.credentialsStored
          ? 'Für ein anderes imitiertes Konto die Schlüsseldatei neu wählen'
          : 'Schlüsseldatei fehlt',
      )
    }
    const subject = values.subject.trim()
    at(
      'subject',
      '„Meine Ablage“ braucht ein imitiertes Konto',
      subject === '' && values.scopes.some((s) => s.kind === 'myDrive'),
    )
    at(
      'subject',
      'Imitiertes Konto ist eine E-Mail-Adresse',
      subject !== '' && !/^[^@\s]+@[^@\s]+$/.test(subject),
    )
    at('scopes', 'Mindestens einen Bereich wählen', values.scopes.length === 0)
    at(
      'scopes',
      `Höchstens ${MAX_GOOGLE_DRIVE_SCOPES} Bereiche`,
      values.scopes.length > MAX_GOOGLE_DRIVE_SCOPES,
    )
    return list
  },
  rowOf: (field) => (field === 'scopes' ? 'scope' : 'connection'),
  probe: (values, fields) =>
    fields.probeRequest<SourceConnectionTestRequest>({
      sourceType: 'GOOGLE_DRIVE',
      sourceUrl: GOOGLE_DRIVE_API,
      sourceCredentials: values.keyFile || undefined,
      sourceSettings: googleDriveSettingsOf(values),
    }),
}
