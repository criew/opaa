import { describe, expect, it } from 'vitest'
import type { TourAnchorName } from '@/shared/ui/a11y/tourAnchor'
import { OPTIONAL_TOUR_ANCHORS, tourStepsFor } from './tourSteps'

const ALL_OPTIONAL: ReadonlySet<TourAnchorName> = new Set(OPTIONAL_TOUR_ANCHORS)
const NONE: ReadonlySet<TourAnchorName> = new Set()

const ids = (isSystemAdmin: boolean, isWide: boolean, present = ALL_OPTIONAL) =>
  tourStepsFor({ isSystemAdmin, isWide, present }).map((step) => step.id)

describe('tourStepsFor', () => {
  it('shows every station from 900 px on, without the administration for others', () => {
    expect(ids(false, true)).toEqual([
      'ask',
      'check',
      'knowledge',
      'filter',
      'prompts',
      'attach',
      'dictate',
      'new-chat',
      'chats',
      'spaces',
      'catalog',
      'handbook',
      'notifications',
      'again',
    ])
  })

  it('adds the administration before the last station for the system administration', () => {
    const stations = ids(true, true)
    expect(stations).toHaveLength(15)
    expect(stations.slice(-3)).toEqual(['notifications', 'admin', 'again'])
  })

  it('leaves out an optional station whose element is not on screen', () => {
    expect(ids(false, true, NONE)).toEqual([
      'ask',
      'check',
      'knowledge',
      'new-chat',
      'chats',
      'spaces',
      'catalog',
      'notifications',
      'again',
    ])
    expect(ids(false, true, new Set<TourAnchorName>(['attach']))).toContain('attach')
    expect(ids(false, true, new Set<TourAnchorName>(['attach']))).not.toContain('dictate')
  })

  it('keeps the stations at the input below 900 px and collapses the drawer into one step', () => {
    expect(ids(false, false)).toEqual([
      'ask',
      'check',
      'knowledge',
      'filter',
      'prompts',
      'attach',
      'dictate',
      'notifications',
      'menu',
    ])
    expect(ids(true, false, NONE)).toEqual(['ask', 'check', 'knowledge', 'notifications', 'menu'])
  })

  it('names the drawer in one sentence, and "Admin" only for that role', () => {
    const user = tourStepsFor({ isSystemAdmin: false, isWide: false, present: NONE }).at(-1)
    const admin = tourStepsFor({ isSystemAdmin: true, isWide: false, present: NONE }).at(-1)

    expect(user?.title).toBe('Alles Weitere im Menü')
    expect(user?.anchor).toBe('menu')
    expect(user?.text).toBe(
      'Hinter diesem Knopf finden Sie Ihren privaten Bereich, die Spaces Ihrer Teams, Ihre Chats und den Katalog mit Bibliotheken und Prompts – und über Ihr Profil starten Sie diesen Rundgang noch einmal.',
    )
    expect(admin?.text).toBe(
      'Hinter diesem Knopf finden Sie Ihren privaten Bereich, die Spaces Ihrer Teams, Ihre Chats und den Katalog mit Bibliotheken und Prompts, unter „Admin“ die Verwaltung von Konten, Gruppen und KI-Modellen – und über Ihr Profil starten Sie diesen Rundgang noch einmal.',
    )
    expect(admin?.text.match(/\./g)).toHaveLength(1)
  })

  it('points every station at its own element and shows the sample answer where answers are checked', () => {
    const steps = tourStepsFor({ isSystemAdmin: true, isWide: true, present: ALL_OPTIONAL })
    expect(new Set(steps.map((step) => step.anchor)).size).toBe(steps.length)
    expect(steps.filter((step) => step.withAnswerSample).map((step) => step.id)).toEqual(['check'])
    for (const step of steps) {
      expect(step.title.length).toBeGreaterThan(0)
      expect(step.text.length).toBeGreaterThan(0)
    }
  })

  it('says that chats and attachments stay private', () => {
    const text = (id: string) =>
      tourStepsFor({ isSystemAdmin: false, isWide: true, present: ALL_OPTIONAL }).find(
        (step) => step.id === id,
      )?.text
    expect(text('chats')).toContain('Ihre Chats sieht niemand außer Ihnen.')
    expect(text('spaces')).toContain('Ihre Chats sehen trotzdem nur Sie.')
    expect(text('attach')).toContain('Ihre Anhänge sehen nur Sie')
  })
})
