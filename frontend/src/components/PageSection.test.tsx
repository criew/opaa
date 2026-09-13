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
  ...(import.meta.glob('./chat/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('./metadata/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
}

/**
 * Die Dateien, für die die Regel gilt: der Verwaltungsbereich (#1608) und seit #1609 auch Chat,
 * Katalog und die Space-Ansichten. Nicht in der Liste steht, was gar keinen ruhenden Inhalt trägt
 * (Dialoge als eigene Dateien, Formularseiten ohne Blöcke).
 */
const VERWALTUNGSDATEIEN = Object.keys(QUELLEN).filter((pfad) => {
  if (pfad.startsWith('./admin/') || pfad.includes('/searchadmin/')) return true
  if (pfad.startsWith('./chat/') || pfad.startsWith('./metadata/')) return true
  return [
    'BrandingSettingsPage',
    'UserManagementPage',
    'GroupManagementPage',
    'LlmModelManagementPage',
    'OidcProviderManagementPage',
    'MailSettingsPage',
    'SearchIndexingAdminPage',
    // #1609: außerhalb der Administration
    'LibraryDetailPage',
    'LibraryManagementPage',
    'SpacePage',
    'SpaceManagementPage',
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
 *
 * Mit #1609 kommen die beiden Fälle des Chats dazu. Sie sind der vierte Grund, aus dem ein Rahmen
 * Bedeutung trägt, und in #1609 ausdrücklich benannt:
 *
 * - `MessageBubble`: die Blase grenzt zwei Sprecher voneinander ab. Nur die Frage trägt sie; die
 *   Antwort steht schon als Fließtext ohne Blase. Ohne diese eine Fläche verlöre der Verlauf
 *   seinen Wechsel.
 * - `SourceFootnotes`: kein Kasten um Inhalt, sondern ein 11 px großes Etikett am Treffer
 *   („ohne Angabe im gefilterten Feld") — derselbe Fall wie ein Chip.
 * - `ChatInput`: die Eingabezeile ist ein Bedienelement, und ihr Vorschlagsfeld eine schwebende
 *   Ebene. Beides bringt seine Fläche zu Recht mit.
 * - `LibraryManagementPage`: die Kachel je Bibliothek ist eine `ButtonBase` — ein Bedienelement,
 *   das den Weg in die Detailansicht trägt. Ohne Begrenzung wäre unklar, wie weit die Trefferfläche
 *   reicht; das ist der dritte Fall aus #1608, nicht ein Kasten um ruhenden Inhalt.
 */
const AUSNAHMEN = [
  'BrandingPreview.tsx',
  'MailTemplateEditor.tsx',
  'MailTemplatePreviewPane.tsx',
  'OidcProviderSetupInstructions.tsx',
  'GeneratedPasswordDialog.tsx',
  'SetupLinkDialog.tsx',
  'MessageBubble.tsx',
  'SourceFootnotes.tsx',
  'ChatInput.tsx',
  'LibraryManagementPage.tsx',
]

/**
 * Dateien, die eine **schwebende Ebene** selbst bauen und dafür `Paper` zu Recht verwenden — die
 * Prüfung auf `<Paper` lässt nur sie aus, alle übrigen gelten ausnahmslos.
 *
 * - `ChatInput`: das Vorschlagsfeld über der Eingabezeile (`elevation={4}`). Es liegt über dem
 *   Verlauf, nicht in ihm; genau dafür ist `Paper` da.
 *
 * Im Verwaltungsbereich steht diese Liste leer: Dort baut keine Datei eine schwebende Ebene
 * selbst, Dialoge und Menüs bringen ihre Fläche aus MUI mit.
 */
const SCHWEBENDE_EBENEN = ['ChatInput.tsx']

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
   * Die Regel aus #1608, maschinell gezogen: Kein ruhender Inhalt rahmt sich selbst ein. `Paper`
   * ist die Fläche schwebender Ebenen — Dialoge, Menüs, Popover —, und die bringen sie selbst
   * mit; eine Seite, die sie wieder für einen Inhaltsblock benutzt, fällt hier auf.
   */
  it.each(VERWALTUNGSDATEIEN.filter((p) => !SCHWEBENDE_EBENEN.some((a) => p.endsWith(a))))(
    '%s uses no Paper for resting content',
    (pfad) => {
      expect(QUELLEN[pfad]).not.toContain('<Paper')
    },
  )

  it.each(VERWALTUNGSDATEIEN.filter((p) => !AUSNAHMEN.some((a) => p.endsWith(a))))(
    '%s frames no resting content in a card',
    (pfad) => {
      const quelltext = QUELLEN[pfad]

      if (!SCHWEBENDE_EBENEN.some((a) => pfad.endsWith(a))) {
        expect(quelltext).not.toContain('<Paper')
      }
      // Ein voller Rahmen ringsum ist nur als gestrichelter Leerzustand zulässig („hier wäre
      // etwas"); die Trennung von Einträgen läuft über `borderBottom`. Wo ein Rahmen etwas
      // Fremdes markiert statt Inhalt zu bündeln, steht die Datei oben in AUSNAHMEN — mit Grund.
      // Beide Schreibweisen zählen: `border: 1` und `border: '1px solid'` ergeben dieselbe Kante,
      // und bis #1609 fiel die zweite durch das Raster (fünf Fundstellen, darunter der Kasten um
      // „Metadaten-Pflege").
      const vollerRahmen =
        (quelltext.match(/border: 1,/g)?.length ?? 0) +
        (quelltext.match(/border: '1px solid/g)?.length ?? 0)
      const gestrichelt = quelltext.match(/borderStyle: 'dashed'/g)?.length ?? 0
      expect(vollerRahmen).toBe(gestrichelt)
    },
  )
})
