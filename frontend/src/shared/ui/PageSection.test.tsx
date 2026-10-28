import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import Button from '@mui/material/Button'
import { renderWithProviders } from '../../test/test-utils'
import PageSection from './PageSection'

/**
 * The administration area's source text, read in by Vite (`?raw`) — no file system access,
 * so that the test gets by without Node types.
 */
const QUELLEN = {
  ...(import.meta.glob('../../pages/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../features/*/pages/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../features/*/components/**/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../components/admin/**/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../app/areas/ConnectionSettingsTab.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../components/library/PrivateStorageSection.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../components/searchadmin/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../components/chat/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../components/metadata/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../components/space/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../components/succession/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../components/assets/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../components/prompts/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('./list/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('./markdown/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
}

/**
 * The files the rule applies to: the administration area, chat, catalog and the
 * space views. Not in the list is whatever carries no resting content at all
 * (dialogs as files of their own, form pages without blocks).
 */
const VERWALTUNGSDATEIEN = Object.keys(QUELLEN).filter((pfad) => {
  if (pfad.startsWith('../../components/admin/') || pfad.includes('/searchadmin/')) return true
  if (pfad.startsWith('../../features/') && pfad.includes('/components/')) return true
  if (pfad.startsWith('./list/') || pfad.startsWith('./markdown/')) return true
  if (pfad.startsWith('../../components/chat/') || pfad.startsWith('../../components/metadata/'))
    return true
  // The sections of the space settings are components of their own.
  if (pfad.startsWith('../../components/space/')) return true
  // The operations list is built in a component of its own, not on the page.
  if (pfad.startsWith('../../components/succession/')) return true
  // Sharing section, wizard steps and prompt maintenance that every asset type shares.
  if (pfad.startsWith('../../components/assets/') || pfad.startsWith('../../components/prompts/'))
    return true
  return [
    'BrandingSettingsPage',
    'UserManagementPage',
    'GroupManagementPage',
    'LlmModelManagementPage',
    'OidcProviderManagementPage',
    // The three new pages of the administration
    'CapabilityManagementPage',
    'DirectorySyncPage',
    'ProviderGroupWorklistPage',
    'SuccessionPage',
    'MailSettingsPage',
    'SearchIndexingAdminPage',
    // settings tab of the connections area and its private-storage section
    'ConnectionSettingsTab',
    'PrivateStorageSection',
    // outside the administration
    'LibraryDetailPage',
    'SpacePage',
    'SpaceSettingsPage',
    'PromptLibraryDetailPage',
  ].some((seite) => pfad.endsWith(`${seite}.tsx`))
})

/**
 * Justified exceptions. What they have in common is that their frame does not enclose a content
 * block of the page but marks something foreign within it — the three cases in which a frame
 * carries meaning:
 *
 * - `BrandingPreview`: an image of the application, like a picture frame.
 * - `MailTemplateEditor`, `MailTemplatePreviewPane`: the wording of a message as a
 *   monospace block and the HTML version in an `iframe` — both foreign content in the page,
 *   not content of the page.
 * - `OidcProviderSetupInstructions`: a step's icon field, a 32 px square with an
 *   accent edge, no block.
 * - `GeneratedPasswordDialog`, `SetupLinkDialog`, `HandoverLinkDialog`: the value shown once
 *   for copying — the same case as a code block.
 *
 * The chat's cases are the fourth reason for which a frame carries meaning:
 *
 * - `MessageBubble`: the bubble separates two speakers. Only the question carries it; the
 *   answer is already running text without a bubble. Without this one surface the history would
 *   lose its alternation.
 * - `CopyAnswerButton`: „Kopieren" below an answer is a split control in the same
 *   pill shape as „Belege anzeigen"; the frame joins button and variant arrow into one
 *   hit area.
 * - `EvidenceFooter`: „Belege anzeigen" below an answer is a control in pill shape;
 *   the frame shows how far the hit area with the footnote marks reaches, and bundles
 *   no resting content.
 * - `ChatInput`: the input row is a control, and its suggestion field a floating
 *   layer. Both rightly bring their surface along.
 * - `ChatActionMenu`: the suggestion field of the `/` command above the same input row —
 *   the same floating layer as the `@` field, only moved into a file of its own; the
 *   label „mit Formular" is a label on the entry like a chip.
 * - `SpaceGeneralSection`: the danger zone at the end of the master data. The frame sets
 *   archiving and deleting off from the form above — it marks a boundary instead of bundling
 *   content.
 * - `PromptTextHighlight`, `PromptPreview`: a prompt's wording as a monospace block and the
 *   text filled in from it as a result surface — foreign content in the page, the same case as
 *   the `MailTemplateEditor`.
 * - `AssetTile`: an asset's tile in the catalog and in every selection — a card in the
 *   grid (guidelines 5.4), no content block of a page; the outlined type is a label (5.5).
 * - `assetMarks`: the type label that tile and detail page header share — a
 *   label like a chip (5.5), no block.
 * - `AccessBadge`: the state of a right as a label - outlined like every label (5.5), solid or
 *   dashed by state, no block.
 * - `HandoverScopeSummary`: the four key figure tiles of the account handover (ADR-0033) — cards
 *   in the grid like `AssetTile`, on a login page outside the administration, no content block.
 * - `ExternalAccessTokenValueDialog`: the token value shown once and the setup snippets
 *   as a monospace block — the same case as `GeneratedPasswordDialog`.
 */
const AUSNAHMEN = [
  'BrandingPreview.tsx',
  'MailTemplateEditor.tsx',
  'MailTemplatePreviewPane.tsx',
  'OidcProviderSetupInstructions.tsx',
  'GeneratedPasswordDialog.tsx',
  'SetupLinkDialog.tsx',
  'HandoverLinkDialog.tsx',
  'MessageBubble.tsx',
  'EvidenceFooter.tsx',
  'CopyAnswerButton.tsx',
  'ChatInput.tsx',
  'ChatActionMenu.tsx',
  'SpaceGeneralSection.tsx',
  'PromptTextHighlight.tsx',
  'PromptPreview.tsx',
  'AssetTile.tsx',
  'assetMarks.tsx',
  'AccessBadge.tsx',
  'HandoverScopeSummary.tsx',
  'ExternalAccessTokenValueDialog.tsx',
]

/**
 * Files that build a **floating layer** themselves and rightly use `Paper` for it — the
 * check for `<Paper` skips only them, all others apply without exception.
 *
 * - `ChatInput`: the suggestion field above the input row (`elevation={4}`). It lies above the
 *   history, not in it; that is exactly what `Paper` is for.
 * - `ChatActionMenu`: the suggestion field of the `/` command in the same place, for the same
 *   reason.
 *
 * In the administration area this list is empty: no file there builds a floating layer
 * itself, dialogs and menus bring their surface from MUI.
 */
const SCHWEBENDE_EBENEN = ['ChatInput.tsx', 'ChatActionMenu.tsx']

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
    // two headings, two different identifiers - otherwise one names both areas
    const kennungen = abschnitte.map((el) => el.getAttribute('aria-labelledby'))
    expect(new Set(kennungen).size).toBe(2)
  })

  /**
   * The rule drawn mechanically: no resting content frames itself. `Paper`
   * is the surface of floating layers — dialogs, menus, popovers —, and they bring it
   * themselves; a page that uses it again for a content block is caught here.
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
      // A full frame all around is admissible only as a dashed empty state („hier wäre
      // etwas"); entries are separated via `borderBottom`. Where a frame marks something
      // foreign instead of bundling content, the file is listed in AUSNAHMEN above — with a reason.
      // Both notations count: `border: 1` and `border: '1px solid'` produce the same edge.
      const vollerRahmen =
        (quelltext.match(/border: 1,/g)?.length ?? 0) +
        (quelltext.match(/border: '1px solid/g)?.length ?? 0)
      const gestrichelt = quelltext.match(/borderStyle: 'dashed'/g)?.length ?? 0
      expect(vollerRahmen).toBe(gestrichelt)
    },
  )
})
