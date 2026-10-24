/**
 * Video click paths for the knowledge library sources, one typical case each: a small setup, a
 * large selection and a refused connection. Labels follow the current UI; a variant with other
 * words extends the regular expressions here.
 */

const tile = (h, name) => h.page.getByRole('radio', { name }).or(h.page.getByRole('button', { name }))
const button = (h, name) => h.page.getByRole('button', { name })
const field = (h, label) => h.page.getByLabel(label)

export const videos = [
  {
    name: 'sharepoint-einrichten',
    scenario: 'small',
    async run(h, { scenario }) {
      await h.goto(`/libraries/new?mockScenario=${scenario}`)
      await h.caption('SharePoint-Quelle einrichten')
      await h.click(tile(h, /^SharePoint/), 'Kachel SharePoint')
      await h.click(button(h, /^Weiter$/), 'Weiter')
      await h.click(h.page.getByRole('radio', { name: /Microsoft 365 Rheinfurt/ }).or(button(h, /Microsoft 365 Rheinfurt/)), 'Zugang')
      await h.caption('')
      await h.type(field(h, /Adresse der Site/), 'https://rheinfurt.sharepoint.com/sites/rathaus', 'Adresse der Site')
      await h.click(button(h, /^Site öffnen$/), 'Site öffnen')
      await h.click(h.page.getByRole('checkbox', { name: /Dokumente/ }), 'Dokumentbibliothek')
      await h.click(button(h, /^Verbindung testen$/), 'Verbindung testen')
      await h.click(button(h, /^Weiter$/), 'Weiter')
    },
  },
  {
    name: 'nextcloud-viele-ordner',
    scenario: 'scale',
    async run(h, { scenario }) {
      await h.goto(`/libraries/new?mockScenario=${scenario}`)
      await h.caption('Nextcloud mit 200 Ordnern je Ebene')
      await h.click(tile(h, /^Nextcloud/), 'Kachel Nextcloud')
      await h.click(button(h, /^Weiter$/), 'Weiter')
      await h.click(h.page.getByRole('radio', { name: /Eigene Adresse/ }).or(button(h, /Eigene Adresse/)), 'Eigene Adresse')
      await h.caption('')
      await h.type(field(h, /Adresse der Nextcloud/), 'https://cloud.rheinfurt.example', 'Adresse')
      await h.type(field(h, /Technischer Nutzer/), 'svc-opaa', 'Nutzer')
      await h.type(field(h, /App-Passwort/), 'app-passwort', 'App-Passwort')
      await h.click(button(h, /^Ordner laden$/), 'Ordner laden')
      for (let i = 0; i < 3; i++) await h.click(button(h, /übernehmen$/), 'Ordner übernehmen')
      await h.page.mouse.wheel(0, 600)
      await h.pause()
      await h.click(button(h, /^Verbindung testen$/), 'Verbindung testen')
    },
  },
  {
    name: 's3-verbindung-abgelehnt',
    scenario: 'failed',
    async run(h, { scenario }) {
      await h.goto(`/libraries/new?mockScenario=${scenario}`)
      await h.caption('S3: Anmeldung wird abgelehnt')
      await h.click(tile(h, /^S3/), 'Kachel S3')
      await h.click(button(h, /^Weiter$/), 'Weiter')
      await h.caption('')
      await h.type(field(h, /Endpoint|Adresse/), 'https://minio.intern.example:9000', 'Endpoint')
      await h.type(field(h, /Access Key|Zugriffsschlüssel/), 'OPAAINDEXER', 'Access Key')
      await h.type(field(h, /Secret Key|Geheimer Schlüssel/), 'falsch', 'Secret Key')
      await h.click(button(h, /^Buckets laden$/), 'Buckets laden')
      await h.click(button(h, /^Verbindung testen$/), 'Verbindung testen')
      await h.pause(1500)
    },
  },
]
