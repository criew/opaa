import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import Button from '@mui/material/Button'
import { renderWithProviders } from '../test/test-utils'
import PageSection from './PageSection'

/**
 * Der Quelltext des Verwaltungsbereichs, von Vite eingelesen (`?raw`) — kein Dateisystemzugriff,
 * damit der Test ohne Node-Typen auskommt.
 */
const QUELLEN = {
  ...(import.meta.glob('../pages/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('./admin/**/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../components/searchadmin/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
}

/**
 * Die Dateien des Verwaltungsbereichs. Seiten außerhalb (Chat, Katalog, Space-Ansichten) sind
 * nicht Gegenstand von #1608 und stehen deshalb nicht in dieser Liste.
 */
const VERWALTUNGSDATEIEN = Object.keys(QUELLEN).filter((pfad) => {
  if (pfad.startsWith('./admin/') || pfad.includes('/searchadmin/')) return true
  return [
    'BrandingSettingsPage',
    'UserManagementPage',
    'GroupManagementPage',
    'LlmModelManagementPage',
    'OidcProviderManagementPage',
    'MailSettingsPage',
    'SearchIndexingAdminPage',
  ].some((seite) => pfad.endsWith(`${seite}.tsx`))
})

/**
 * Begründete Ausnahmen. Sie eint, dass ihr Rahmen nicht einen Inhaltsblock der Seite umschließt,
 * sondern etwas Fremdes darin markiert — die drei Fälle, in denen ein Rahmen Bedeutung trägt:
 *
 * - `BrandingPreview`: ein Abbild der Anwendung, wie ein Bilderrahmen.
 * - `MailTemplateEditor`, `MailTemplatePreviewPane`: der Wortlaut einer Nachricht als
 *   Monospace-Block und die HTML-Fassung in einem `iframe` — beides fremder Inhalt in der Seite,
 *   nicht Inhalt der Seite.
 * - `OidcProviderSetupInstructions`: das Symbolfeld eines Schritts, ein 32 px großes Quadrat mit
 *   Akzentkante, kein Block.
 * - `GeneratedPasswordDialog`, `SetupLinkDialog`: der einmalig angezeigte Wert zum Abschreiben —
 *   derselbe Fall wie ein Codeblock.
 */
const AUSNAHMEN = [
  'BrandingPreview.tsx',
  'MailTemplateEditor.tsx',
  'MailTemplatePreviewPane.tsx',
  'OidcProviderSetupInstructions.tsx',
  'GeneratedPasswordDialog.tsx',
  'SetupLinkDialog.tsx',
]

describe('PageSection', () => {
  it('renders the head over its content and names the section for assistive tech', () => {
    renderWithProviders(
      <PageSection title="Verbindung" description="Gilt für alles.">
        <p>Inhalt</p>
      </PageSection>,
    )

    const abschnitt = screen.getByRole('region', { name: 'Verbindung' })
    expect(within(abschnitt).getByRole('heading', { name: 'Verbindung' })).toBeInTheDocument()
    expect(within(abschnitt).getByText('Gilt für alles.')).toBeInTheDocument()
    expect(within(abschnitt).getByText('Inhalt')).toBeInTheDocument()
  })

  it('puts the section action next to the head', () => {
    renderWithProviders(
      <PageSection title="Vorlagen" action={<Button>Zurücksetzen</Button>}>
        <p>Inhalt</p>
      </PageSection>,
    )

    const abschnitt = screen.getByRole('region', { name: 'Vorlagen' })
    expect(within(abschnitt).getByRole('button', { name: 'Zurücksetzen' })).toBeInTheDocument()
  })

  it('gives each section its own name, even with the same title twice', () => {
    renderWithProviders(
      <>
        <PageSection title="Verbindung">
          <p>erster</p>
        </PageSection>
        <PageSection title="Verbindung">
          <p>zweiter</p>
        </PageSection>
      </>,
    )

    const abschnitte = screen.getAllByRole('region', { name: 'Verbindung' })
    expect(abschnitte).toHaveLength(2)
    // zwei Überschriften, zwei verschiedene Kennungen - sonst benennt eine beide Bereiche
    const kennungen = abschnitte.map((el) => el.getAttribute('aria-labelledby'))
    expect(new Set(kennungen).size).toBe(2)
  })

  /**
   * Die Regel aus #1608, maschinell gezogen: Im Verwaltungsbereich rahmt kein ruhender Inhalt
   * sich selbst ein. `Paper` ist die Fläche schwebender Ebenen — Dialoge, Menüs, Popover —, und
   * die bringen sie selbst mit; eine Seite, die sie wieder für einen Inhaltsblock benutzt, fällt
   * hier auf.
   */
  it.each(VERWALTUNGSDATEIEN)('%s uses no Paper for resting content', (pfad) => {
    // Gilt ohne Ausnahme: `Paper` ist die Fläche schwebender Ebenen, und die bringen sie selbst
    // mit. Auch die vier Ausnahmen unten rahmen mit `border`, nicht mit einer Fläche.
    expect(QUELLEN[pfad]).not.toContain('<Paper')
  })

  it.each(VERWALTUNGSDATEIEN.filter((p) => !AUSNAHMEN.some((a) => p.endsWith(a))))(
    '%s frames no resting content in a card',
    (pfad) => {
      const quelltext = QUELLEN[pfad]

      expect(quelltext).not.toContain('<Paper')
      // Ein voller Rahmen ringsum ist nur als gestrichelter Leerzustand zulässig („hier wäre
      // etwas"); die Trennung von Einträgen läuft über `borderBottom`. Wo ein Rahmen etwas
      // Fremdes markiert statt Inhalt zu bündeln, steht die Datei oben in AUSNAHMEN — mit Grund.
      const vollerRahmen = quelltext.match(/border: 1,/g)?.length ?? 0
      const gestrichelt = quelltext.match(/borderStyle: 'dashed'/g)?.length ?? 0
      expect(vollerRahmen).toBe(gestrichelt)
    },
  )
})
