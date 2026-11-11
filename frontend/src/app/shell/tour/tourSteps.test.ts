import { describe, expect, it } from 'vitest'
import { tourStepsFor } from './tourSteps'

const ids = (isSystemAdmin: boolean, isWide: boolean) =>
  tourStepsFor({ isSystemAdmin, isWide }).map((step) => step.id)

describe('tourStepsFor', () => {
  it('shows the stations of the epic from 900 px on, without the administration for others', () => {
    expect(ids(false, true)).toEqual(['ask', 'knowledge', 'spaces', 'catalog', 'again'])
  })

  it('adds the administration before the last station for the system administration', () => {
    expect(ids(true, true)).toEqual(['ask', 'knowledge', 'spaces', 'catalog', 'admin', 'again'])
  })

  it('collapses the drawer stations into one step at the menu button below 900 px', () => {
    expect(ids(false, false)).toEqual(['ask', 'knowledge', 'menu'])
    expect(ids(true, false)).toEqual(['ask', 'knowledge', 'menu'])
  })

  it('names spaces, catalog and profile in one sentence, and "Admin" only for that role', () => {
    const [, , user] = tourStepsFor({ isSystemAdmin: false, isWide: false })
    const [, , admin] = tourStepsFor({ isSystemAdmin: true, isWide: false })

    expect(user.title).toBe('Alles Weitere im Menü')
    expect(user.anchor).toBe('menu')
    expect(user.text).toBe(
      'Hinter diesem Knopf finden Sie Ihren privaten Bereich, die Spaces Ihrer Teams und den Katalog mit Bibliotheken und Prompts – und über Ihr Profil starten Sie diesen Rundgang noch einmal.',
    )
    expect(admin.text).toBe(
      'Hinter diesem Knopf finden Sie Ihren privaten Bereich, die Spaces Ihrer Teams und den Katalog mit Bibliotheken und Prompts, unter „Admin“ die Verwaltung von Konten, Gruppen und KI-Modellen – und über Ihr Profil starten Sie diesen Rundgang noch einmal.',
    )
    expect(admin.text.match(/\./g)).toHaveLength(1)
  })

  it('carries the wording of the epic', () => {
    const steps = tourStepsFor({ isSystemAdmin: true, isWide: true })
    expect(steps.map(({ anchor, title, text }) => ({ anchor, title, text }))).toEqual([
      {
        anchor: 'chat-input',
        title: 'Fragen stellen',
        text: 'Schreiben Sie Ihre Frage hier hinein, so wie Sie einen Menschen fragen würden. In der Antwort stehen kleine Zahlen: Jede zeigt, aus welchem Dokument die Information stammt. Ein Klick auf die Zahl öffnet genau diese Stelle.',
      },
      {
        anchor: 'space-knowledge',
        title: 'Worauf die Antwort sich stützt',
        text: 'OPAA sucht die Antwort in Dokumenten, die Sie lesen dürfen. „@Space-Wissen“ heißt: alle Dokumente, die zu diesem Space gehören. Tippen Sie @, wenn nur bestimmte Bibliotheken – also Sammlungen von Dokumenten – gelten sollen.',
      },
      {
        anchor: 'spaces',
        title: 'Privat oder im Team',
        text: 'Ihren privaten Bereich sehen nur Sie. Ein Space ist wie ein gemeinsamer Ordner für ein Team: Alle Mitglieder sehen dieselben Chats und Dokumente.',
      },
      {
        anchor: 'catalog',
        title: 'Wissen finden',
        text: 'Im Katalog finden Sie alle Bibliotheken, die Sie lesen dürfen, und alle Prompts – das sind fertige Vorlagen für Fragen. Von hier aus fügen Sie sie einem Space hinzu.',
      },
      {
        anchor: 'admin',
        title: 'Systemverwaltung',
        text: 'Unter „Admin“ richten Sie OPAA für alle ein: Konten, Gruppen und die KI-Modelle. Diesen Eintrag sehen nur Personen aus der Systemverwaltung.',
      },
      {
        anchor: 'profile',
        title: 'Rundgang wiederholen',
        text: 'Sie möchten den Rundgang noch einmal sehen? Klicken Sie auf Ihr Profil und dann auf „Rundgang starten“.',
      },
    ])
    expect(steps[0].withAnswerSample).toBe(true)
  })
})
