/** Where the handbook is published: main under the root, every release under v<version>/. */
export const HANDBOOK_BASE_URL = 'https://opaa.io/handbuch/'

/**
 * The handbook page for a view, first match wins. Paths are those of the built site
 * (docs/handbuch/<page>.md becomes <page>.html); a view without an entry opens the start page.
 */
const HANDBOOK_PAGES: ReadonlyArray<readonly [RegExp, string]> = [
  [/^\/spaces$/, 'benutzer/erste-schritte.html'],
  [/^\/spaces\/new$/, 'benutzer/space-anlegen.html'],
  [/^\/spaces\/[^/]+\/settings\/members$/, 'benutzer/space-mitglieder.html'],
  [/^\/spaces\/[^/]+\/settings\/content$/, 'benutzer/space-inhalte.html'],
  [/^\/spaces\/[^/]+\/(settings|manage)(\/|$)/, 'benutzer/space-einstellungen.html'],
  [/^\/spaces\/[^/]+\/chats$/, 'benutzer/chats.html'],
  [/^\/(chat|spaces\/[^/]+)(\/|$)/, 'benutzer/fragen-stellen.html'],
  [/^\/catalog(\/|$)/, 'benutzer/katalog.html'],
  [/^\/libraries\/new$/, 'benutzer/bibliothek-anlegen.html'],
  [/^\/libraries\/[^/]+/, 'benutzer/bibliothek-aktuell-halten.html'],
  [/^\/prompts\/new$/, 'benutzer/prompt-bibliothek-anlegen.html'],
  [/^\/prompts\/[^/]+\/freigaben$/, 'benutzer/arbeitsmittel-teilen.html'],
  [/^\/prompts\/[^/]+/, 'benutzer/prompts-verwenden.html'],
  [/^\/settings\/tokens$/, 'benutzer/ki-werkzeug-einrichten.html'],
  [/^\/settings\/accounts$/, 'benutzer/konto-verbinden.html'],
  [/^\/settings\/data$/, 'benutzer/meine-daten.html'],
  [/^\/settings\/groups$/, 'benutzer/gruppe-pflegen.html'],
  [/^\/settings\/uploads$/, 'benutzer/anhaengen-und-diktieren.html'],
  [/^\/settings(\/general)?$/, 'benutzer/erste-schritte.html'],
  [/^\/admin\/users(\/|$)/, 'administration/konten.html'],
  [/^\/admin\/(groups|directory-sync)(\/|$)/, 'administration/gruppen.html'],
  [/^\/admin\/identity-providers(\/|$)/, 'administration/anmeldung-einstellen.html'],
  [/^\/admin\/models(\/|$)/, 'administration/modelle.html'],
  [/^\/admin\/search(\/|$)/, 'administration/suche-und-indexierung.html'],
  [/^\/admin\/succession(\/|$)/, 'administration/ausscheiden.html'],
  [/^\/admin\/mail(\/|$)/, 'administration/e-mail.html'],
  [/^\/admin\/malware-scan(\/|$)/, 'administration/virenpruefung.html'],
  [/^\/admin\/branding(\/|$)/, 'administration/branding.html'],
  [/^\/admin\/privacy(\/|$)/, 'administration/datenschutz.html'],
  [/^\/admin\/settings-transfer(\/|$)/, 'administration/einstellungen-uebertragen.html'],
  [/^\/admin\/capabilities(\/|$)/, 'administration/anlegerechte.html'],
  [
    /^\/admin\/(connections\/(external-tools|service-accounts)|external-access)(\/|$)/,
    'administration/externe-ki-werkzeuge.html',
  ],
  [/^\/admin\/(connections|connection-profiles)(\/|$)/, 'administration/zugaenge.html'],
  [/^\/revision\/verbindungsprotokoll$/, 'administration/verbindungsprotokoll.html'],
  [/^\/revision\/rechtehistorie$/, 'administration/stichtagsauskunft.html'],
  [/^\/revision\/rollenantraege$/, 'administration/systemrollen.html'],
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
