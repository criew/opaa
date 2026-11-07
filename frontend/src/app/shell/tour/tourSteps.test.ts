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
      'Im Menü wechseln Sie zwischen Ihrem privaten Bereich und Spaces, finden im Katalog alle Bibliotheken und Prompts und starten über Ihr Profil diesen Rundgang erneut.',
    )
    expect(admin.text).toBe(
      'Im Menü wechseln Sie zwischen Ihrem privaten Bereich und Spaces, finden im Katalog alle Bibliotheken und Prompts, verwalten unter „Admin“ Konten, Gruppen und Modelle und starten über Ihr Profil diesen Rundgang erneut.',
    )
    expect(admin.text.match(/\./g)).toHaveLength(1)
  })

  it('carries the wording of the epic', () => {
    const steps = tourStepsFor({ isSystemAdmin: true, isWide: true })
    expect(steps.map(({ anchor, title, text }) => ({ anchor, title, text }))).toEqual([
      {
        anchor: 'chat-input',
        title: 'Fragen stellen',
        text: 'Fragen Sie in eigenen Worten. Jede Antwort nennt ihre Fundstellen als Ziffern; ein Klick zeigt die Stelle im Dokument.',
      },
      {
        anchor: 'space-knowledge',
        title: 'Worauf die Antwort sich stützt',
        text: '@Space-Wissen ist das Wissen, das diesem Space zugeordnet ist und das Sie lesen dürfen. Mit @ wählen Sie einzelne Bibliotheken.',
      },
      {
        anchor: 'spaces',
        title: 'Privat oder im Team',
        text: 'Im privaten Bereich arbeiten Sie allein. Ein Space teilt Chats und Wissen mit seinen Mitgliedern.',
      },
      {
        anchor: 'catalog',
        title: 'Wissen finden',
        text: 'Der Katalog zeigt alle Bibliotheken und Prompts, die Sie lesen dürfen. Von dort ordnen Sie sie einem Space zu.',
      },
      {
        anchor: 'admin',
        title: 'Systemverwaltung',
        text: 'Unter „Admin“ verwalten Sie Konten, Gruppen und Modelle; diesen Eintrag sehen nur Personen in der Systemverwaltung.',
      },
      {
        anchor: 'profile',
        title: 'Rundgang wiederholen',
        text: 'Über Ihr Profil starten Sie diesen Rundgang jederzeit erneut.',
      },
    ])
    expect(steps[0].withAnswerSample).toBe(true)
  })
})
