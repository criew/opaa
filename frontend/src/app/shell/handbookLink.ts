/** Where the handbook is published: main under the root, every release under v<version>/. */
export const HANDBOOK_BASE_URL = 'https://opaa.io/handbuch/'

/**
 * The handbook page for a view, first match wins. Paths are those of the built site
 * (docs/handbuch/<page>.md becomes <page>.html); a view without an entry opens the start page.
 */
const HANDBOOK_PAGES: ReadonlyArray<readonly [RegExp, string]> = [
  [/^\/spaces$/, 'arbeiten/erste-schritte.html'],
  [/^\/spaces\/new$/, 'arbeiten/space-anlegen.html'],
  [/^\/spaces\/[^/]+\/settings\/members$/, 'pflegen/space-mitglieder.html'],
  [/^\/spaces\/[^/]+\/settings\/content$/, 'pflegen/space-inhalte.html'],
  [/^\/spaces\/[^/]+\/(settings|manage)(\/|$)/, 'pflegen/space-einstellungen.html'],
  [/^\/spaces\/[^/]+\/chats$/, 'arbeiten/chats.html'],
  [/^\/(chat|spaces\/[^/]+)(\/|$)/, 'arbeiten/fragen-stellen.html'],
  [/^\/catalog(\/|$)/, 'arbeiten/katalog.html'],
  [/^\/libraries\/new$/, 'arbeiten/bibliothek-anlegen.html'],
  [/^\/libraries\/[^/]+/, 'pflegen/bibliothek-aktuell-halten.html'],
  [/^\/prompts\/new$/, 'arbeiten/prompt-bibliothek-anlegen.html'],
  [/^\/prompts\/[^/]+\/freigaben$/, 'pflegen/arbeitsmittel-teilen.html'],
  [/^\/prompts\/[^/]+/, 'arbeiten/prompts-verwenden.html'],
  [/^\/settings\/tokens$/, 'arbeiten/zugangstoken.html'],
  [/^\/settings\/accounts$/, 'arbeiten/konto-verbinden.html'],
  [/^\/settings\/data$/, 'arbeiten/meine-daten.html'],
  [/^\/settings\/groups$/, 'pflegen/gruppe-pflegen.html'],
  [/^\/settings\/uploads$/, 'arbeiten/anhaengen-und-diktieren.html'],
  [/^\/settings(\/general)?$/, 'arbeiten/erste-schritte.html'],
  [/^\/admin\/users(\/|$)/, 'verwalten/konten.html'],
  [/^\/admin\/(groups|directory-sync)(\/|$)/, 'verwalten/gruppen.html'],
  [/^\/admin\/identity-providers(\/|$)/, 'verwalten/anmeldung-einstellen.html'],
  [/^\/admin\/models(\/|$)/, 'verwalten/modelle.html'],
  [/^\/admin\/search(\/|$)/, 'verwalten/suche-und-indexierung.html'],
  [/^\/admin\/succession(\/|$)/, 'verwalten/ausscheiden.html'],
  [/^\/admin\/mail(\/|$)/, 'verwalten/e-mail.html'],
  [/^\/admin\/branding(\/|$)/, 'verwalten/branding.html'],
  [/^\/admin\/privacy(\/|$)/, 'verwalten/datenschutz.html'],
  [/^\/admin\/settings-transfer(\/|$)/, 'verwalten/einstellungen-uebertragen.html'],
  [/^\/admin\/capabilities(\/|$)/, 'verwalten/anlegerechte.html'],
  [
    /^\/admin\/(connections\/external-tools|external-access)(\/|$)/,
    'verwalten/externe-ki-werkzeuge.html',
  ],
  [/^\/admin\/(connections|connection-profiles)(\/|$)/, 'verwalten/zugaenge.html'],
  [/^\/revision\/verbindungsprotokoll$/, 'verwalten/verbindungsprotokoll.html'],
  [/^\/revision\/rechtehistorie$/, 'verwalten/stichtagsauskunft.html'],
  [/^\/revision\/rollenantraege$/, 'verwalten/systemrollen.html'],
]

/**
 * The handbook version that belongs to a build: a release (X.Y.Z[-<pre>]) has its own, every
 * other build - 0.0.0-* for development, smoke tests and main images - the current state of main.
 */
export function handbookVersionPath(version: string): string {
  return /^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(version) && !version.startsWith('0.0.0-')
    ? `v${version}/`
    : ''
}

/** The handbook page that explains the view at `pathname`, in the handbook of `version`. */
export function handbookUrl(pathname: string, version: string): string {
  const page = HANDBOOK_PAGES.find(([pattern]) => pattern.test(pathname))?.[1] ?? 'index.html'
  return `${HANDBOOK_BASE_URL}${handbookVersionPath(version)}${page}`
}

/** Every page the mapping names, for the test that keeps it in step with docs/handbuch/. */
export const HANDBOOK_PAGE_PATHS: readonly string[] = HANDBOOK_PAGES.map(([, page]) => page)
