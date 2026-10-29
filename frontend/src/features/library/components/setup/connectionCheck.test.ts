import { describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { outcomeOf, useConnectionCheck } from './connectionCheck'
import { testLibrarySource } from '../../api/libraryApi'

vi.mock('../../api/libraryApi', () => ({ testLibrarySource: vi.fn() }))

const stagesOf = (outcome: ReturnType<typeof outcomeOf>) =>
  outcome.stages.map((stage) => `${stage.label} ${stage.text}`)

describe('outcomeOf (#3152)', () => {
  describe('a source with an address to enter', () => {
    it('names the address when it was not reached', () => {
      const outcome = outcomeOf(
        't',
        { reachable: false, message: 'Zeitüberschreitung' },
        true,
        false,
      )
      expect(outcome.state).toBe('failed')
      expect(stagesOf(outcome)).toEqual([
        'Adresse nicht erreichbar',
        'Anmeldung nicht geprüft',
        'Rechte nicht geprüft',
      ])
    })

    // regression guard for #3175: S3 and Confluence answer a TLS, network or blocked-target
    // failure with credentialsVerified null - the address is to blame, not the sign-in
    it.each([
      [
        'S3 over TLS',
        'Die TLS-Verbindung zum Objektspeicher ist fehlgeschlagen (Protokollfehler).',
      ],
      [
        'S3 unreachable',
        'Der Objektspeicher ist nicht erreichbar: Die Verbindung wurde abgelehnt.',
      ],
      ['Confluence over TLS', 'TLS-Verbindung zu wiki.example.org fehlgeschlagen.'],
    ])('names the address when the probe said nothing about the secret: %s', (_case, message) => {
      const outcome = outcomeOf(
        't',
        { reachable: false, credentialsVerified: null, message, details: { scopes: [] } },
        true,
        false,
      )
      expect(stagesOf(outcome)).toEqual([
        'Adresse nicht erreichbar',
        'Anmeldung nicht geprüft',
        'Rechte nicht geprüft',
      ])
    })

    it('names the missing rights behind an accepted sign-in', () => {
      const outcome = outcomeOf(
        't',
        {
          reachable: false,
          credentialsVerified: true,
          message:
            'Bereich „dokumente“: Der Bucket „dokumente“ darf mit diesen Zugangsdaten nicht aufgelistet werden (s3:ListBucket fehlt).',
          details: {
            scopes: [
              { bucket: 'dokumente', prefix: '', bucketReachable: true, listAllowed: false },
            ],
          },
        },
        true,
        false,
      )
      expect(outcome.state).toBe('failed')
      expect(stagesOf(outcome)).toEqual([
        'Adresse erreichbar',
        'Anmeldung angenommen',
        'Rechte 1 von 1 nicht lesbar',
      ])
      expect(outcome.unreadable).toEqual(['dokumente'])
    })

    it('names the refused sign-in behind a reached address', () => {
      const outcome = outcomeOf(
        't',
        { reachable: false, credentialsVerified: false, message: 'Abgelehnt' },
        true,
        false,
      )
      expect(stagesOf(outcome)).toEqual([
        'Adresse erreichbar',
        'Anmeldung abgelehnt',
        'Rechte nicht geprüft',
      ])
    })
    // regression guard for #3195: a reached source that did not verify the secret (Confluence
    // after a detection-only test) never reads as an accepted sign-in
    it('does not pass a reached source whose sign-in was not verified', () => {
      const outcome = outcomeOf(
        't',
        {
          reachable: true,
          credentialsVerified: false,
          message: 'Confluence Cloud erkannt. Bitte E-Mail-Adresse und API-Token angeben.',
          details: { edition: 'CLOUD' },
        },
        true,
        false,
      )
      expect(outcome.state).toBe('failed')
      expect(stagesOf(outcome)).not.toContain('Anmeldung angenommen')
      expect(stagesOf(outcome)).toEqual([
        'Adresse erreichbar',
        'Anmeldung nicht geprüft',
        'Rechte nicht geprüft',
      ])
    })

    it('still passes a reached source without a sign-in', () => {
      const outcome = outcomeOf(
        't',
        { reachable: true, credentialsVerified: false, message: 'ok' },
        false,
        false,
      )
      expect(outcome.state).toBe('passed')
      expect(stagesOf(outcome)).toEqual([
        'Adresse erreichbar',
        'Anmeldung entfällt',
        'Rechte lesbar',
      ])
    })
  })

  describe('a source with a fixed address', () => {
    it('never names an address that was never entered', () => {
      for (const result of [
        { reachable: true, credentialsVerified: true, message: 'ok' },
        { reachable: false, credentialsVerified: true, message: 'fremdes OneDrive' },
        { reachable: false, credentialsVerified: false, message: 'Abgelehnt' },
        { reachable: false, message: 'Bitte zuerst das Microsoft-Konto verbinden.' },
      ]) {
        const outcome = outcomeOf('t', result, true, true)
        expect(outcome.stages.map((stage) => stage.label)).toEqual(['Anmeldung', 'Rechte'])
      }
    })

    it('reads an unreachable source behind an accepted sign-in as missing rights', () => {
      const outcome = outcomeOf(
        't',
        { reachable: false, credentialsVerified: true, message: 'fremdes OneDrive' },
        true,
        true,
      )
      expect(outcome.state).toBe('failed')
      expect(stagesOf(outcome)).toEqual(['Anmeldung angenommen', 'Rechte nicht lesbar'])
    })

    it('counts the scopes that could not be read', () => {
      const outcome = outcomeOf(
        't',
        {
          reachable: false,
          credentialsVerified: true,
          message: 'Die Dokumentbibliothek ist für die Anwendung nicht erreichbar.',
          details: { libraries: [{ driveId: 'd-1', reachable: false }] },
        },
        true,
        true,
      )
      expect(stagesOf(outcome)).toEqual(['Anmeldung angenommen', 'Rechte 1 von 1 nicht lesbar'])
      expect(outcome.unreadable).toEqual(['d-1'])
    })

    it('names the refused sign-in', () => {
      expect(
        stagesOf(
          outcomeOf(
            't',
            { reachable: false, credentialsVerified: false, message: 'Abgelehnt' },
            true,
            true,
          ),
        ),
      ).toEqual(['Anmeldung abgelehnt', 'Rechte nicht geprüft'])
    })

    it.each([
      ['Google Drive without a scope', 'Bitte mindestens einen Bereich wählen.'],
      ['SharePoint without a document library', 'Bitte mindestens eine Dokumentbibliothek wählen.'],
      ['OneDrive without an account', 'Bitte zuerst das Microsoft-Konto verbinden.'],
    ])('blames no stage for a refusal before the sign-in: %s', (_case, message) => {
      const outcome = outcomeOf('t', { reachable: false, message }, true, true)
      expect(outcome.state).toBe('failed')
      expect(outcome.message).toBe(message)
      expect(outcome.stages.filter((stage) => stage.state === 'failed')).toEqual([])
      expect(stagesOf(outcome)).toEqual(['Anmeldung nicht geprüft', 'Rechte nicht geprüft'])
    })

    it('does not pass a reached source whose sign-in was not verified', () => {
      const outcome = outcomeOf(
        't',
        { reachable: true, credentialsVerified: false, message: 'Bitte anmelden.' },
        true,
        true,
      )
      expect(outcome.state).toBe('failed')
      expect(stagesOf(outcome)).toEqual(['Anmeldung nicht geprüft', 'Rechte nicht geprüft'])
    })

    it('reads a passed test without an address stage', () => {
      const outcome = outcomeOf(
        't',
        { reachable: true, credentialsVerified: true, message: 'ok' },
        true,
        true,
      )
      expect(outcome.state).toBe('passed')
      expect(stagesOf(outcome)).toEqual(['Anmeldung angenommen', 'Rechte lesbar'])
    })
  })
})

describe('outcomeOf names the unreadable scopes the way the Umfang does (#3144)', () => {
  const partial = (details: Record<string, unknown>) => ({
    reachable: true,
    credentialsVerified: true,
    message: 'Anmeldung erfolgreich; 2 von 3 Bereichen nicht erreichbar.',
    details,
  })

  it('names Google Drive scopes by their shared drive or folder, never by their key', () => {
    const outcome = outcomeOf(
      't',
      partial({
        scopes: [
          { scope: 'drive:0AbcDrive', reachable: false, message: 'kein Mitglied' },
          { scope: 'folder:1XyzFolder', reachable: false, message: 'nicht geteilt' },
          { scope: 'folder:1Readable', reachable: true },
        ],
      }),
      true,
      true,
      {
        sourceType: 'GOOGLE_DRIVE',
        sourceSettings: {
          scopes: [
            { drive: '0AbcDrive', name: 'Projektablage' },
            { folder: '1XyzFolder', name: 'Verträge / 2024' },
            { folder: '1Readable', name: 'Lesbar' },
          ],
        },
      },
    )
    expect(outcome.state).toBe('partial')
    expect(outcome.unreadable).toEqual(['Projektablage', 'Verträge / 2024'])
  })

  it('names a Google Drive scope without a known name by a short stand-in', () => {
    const outcome = outcomeOf(
      't',
      partial({
        scopes: [
          { scope: 'drive:0Unnamed', reachable: false },
          { scope: 'folder:1Unnamed', reachable: false },
          { scope: 'myDrive', reachable: false },
        ],
      }),
      true,
      true,
      {
        sourceType: 'GOOGLE_DRIVE',
        sourceSettings: {
          scopes: [{ drive: '0Unnamed' }, { folder: '1Unnamed', name: '' }, { myDrive: true }],
        },
      },
    )
    expect(outcome.unreadable).toEqual(['unbekannte Ablage', 'unbekannter Ordner', 'Meine Ablage'])
  })

  it('names SharePoint document libraries by their name, never by their drive id', () => {
    const outcome = outcomeOf(
      't',
      partial({
        libraries: [
          { driveId: 'b!drive-1', reachable: false },
          { driveId: 'b!drive-2', reachable: false },
        ],
      }),
      true,
      true,
      {
        sourceType: 'SHAREPOINT',
        sourceSettings: { libraries: [{ driveId: 'b!drive-1', name: 'Dokumente' }] },
      },
    )
    expect(outcome.unreadable).toEqual(['Dokumente', 'unbekannte Bibliothek'])
  })

  it('names Confluence spaces by their name where the selection knows it', () => {
    const outcome = outcomeOf('t', partial({ unreadableSpaces: ['ENG', 'OPS'] }), true, false, {
      sourceType: 'CONFLUENCE',
      sourceSettings: { spaces: [{ key: 'ENG', name: 'Engineering' }] },
    })
    expect(outcome.unreadable).toEqual(['Engineering', 'OPS'])
  })

  it('keeps the bucket and prefix an S3 scope is shown under', () => {
    const outcome = outcomeOf(
      't',
      partial({
        scopes: [
          { bucket: 'dokumente', prefix: 'akten', bucketReachable: true, listAllowed: false },
        ],
      }),
      true,
      false,
      { sourceType: 'S3', sourceSettings: { scopes: [{ bucket: 'dokumente', prefix: 'akten' }] } },
    )
    expect(outcome.unreadable).toEqual(['dokumente/akten'])
  })

  it('resolves the names from the request the check ran with', async () => {
    vi.mocked(testLibrarySource).mockResolvedValue(
      partial({ scopes: [{ scope: 'folder:1XyzFolder', reachable: false }] }),
    )
    const request = {
      sourceType: 'GOOGLE_DRIVE' as const,
      sourceSettings: { scopes: [{ folder: '1XyzFolder', name: 'Verträge' }] },
    }
    const { result } = renderHook(() => useConnectionCheck(request, true, true))
    await act(async () => {
      await result.current.run()
    })
    expect(result.current.outcome?.unreadable).toEqual(['Verträge'])
  })
})
