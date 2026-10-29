import { describe, expect, it } from 'vitest'
import { outcomeOf } from './connectionCheck'

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
