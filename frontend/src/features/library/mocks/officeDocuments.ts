import { RUNDSCHREIBEN_DOCX_BASE64 } from './rundschreibenDocx'

/** The Word file, spreadsheet and mail the mocked answer on Förderfristen cites. */
export const OFFICE_DOCUMENTS = {
  docx: {
    id: 'doc-office-docx',
    fileName: 'rundschreiben-03-2026.docx',
    contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  },
  csv: { id: 'doc-office-csv', fileName: 'fristenliste-2026.csv', contentType: 'text/csv' },
  mail: {
    id: 'doc-office-mail',
    fileName: 'rueckfrage-verwendungsnachweis.eml',
    contentType: 'message/rfc822',
  },
} as const

export const OFFICE_DOCUMENT_IDS: string[] = Object.values(OFFICE_DOCUMENTS).map((d) => d.id)

const FRISTENLISTE_CSV = [
  'Aktenzeichen;Vorhaben;Antragsteller;Abschluss der Maßnahme;Verwendungsnachweis fällig;Status',
  'WF-2026-014;Fassadenbegrünung Marktstraße 4;Café Lindner GbR;31.03.2026;30.09.2026;eingegangen',
  'WF-2026-022;Ladenumbau Kirchgasse 9;Buchhandlung Seidel;30.04.2026;31.10.2026;geprüft',
  'WF-2026-031;Sitzmöbel Rathausplatz;Werbegemeinschaft Mitte e. V.;15.06.2026;15.12.2026;offen',
  'WF-2026-037;Beleuchtung Schaufenster Am Tor 2;Optik Brandt;31.07.2026;31.01.2027;offen',
  'WF-2026-040;Pop-up-Store Leerstand Lange Str. 11;Kreativraum UG;31.08.2026;28.02.2027;offen',
  'WF-2026-046;Barrierefreier Zugang Bäckerei;Bäckerei Hoffmann;30.09.2026;31.03.2027;offen',
].join('\r\n')

const RUECKFRAGE_EML = [
  'From: Jana Brandt <jana.brandt@optik-brandt.example>',
  'To: Förderreferat <foerderung@musterhausen.example>',
  'Cc: Werbegemeinschaft Mitte <info@wg-mitte.example>',
  'Subject: Rückfrage Verwendungsnachweis WF-2026-037',
  'Date: Mon, 16 Nov 2026 09:42:00 +0100',
  'Message-ID: <20261116094200.4711@optik-brandt.example>',
  'MIME-Version: 1.0',
  'Content-Type: text/plain; charset=utf-8',
  'Content-Transfer-Encoding: 8bit',
  '',
  'Sehr geehrte Damen und Herren,',
  '',
  'unsere Schaufensterbeleuchtung ist seit Ende Juli fertig. Die Schlussrechnung des',
  'Elektrikers kommt aber erst im Februar, weil noch eine Nachrüstung aussteht.',
  '',
  'Können wir die Frist für den Verwendungsnachweis um drei Monate verlängern? Den',
  'Sachbericht mit Fotos könnten wir schon jetzt schicken.',
  '',
  'Mit freundlichen Grüßen',
  'Jana Brandt',
  'Optik Brandt, Am Tor 2, Musterhausen',
].join('\r\n')

function bytesOf(base64: string): Uint8Array<ArrayBuffer> {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

/** The original file of an office mock document, or `null` for any other id. */
export function officeDocumentContent(documentId: string): Response | null {
  const entry = Object.values(OFFICE_DOCUMENTS).find((d) => d.id === documentId)
  if (!entry) return null
  const body =
    entry === OFFICE_DOCUMENTS.docx
      ? new Blob([bytesOf(RUNDSCHREIBEN_DOCX_BASE64)], { type: entry.contentType })
      : new Blob([entry === OFFICE_DOCUMENTS.csv ? FRISTENLISTE_CSV : RUECKFRAGE_EML], {
          type: entry.contentType,
        })
  return new Response(body, {
    status: 200,
    headers: {
      'Content-Type': entry.contentType,
      'Content-Disposition': `inline; filename="${entry.fileName}"`,
    },
  })
}

/** The Textfassung passages of an office mock document, as the indexer would cut them. */
export function officeDocumentPassages(documentId: string) {
  if (documentId === OFFICE_DOCUMENTS.docx.id) {
    return [
      'Rundschreiben 03/2026 – Fristen und Nachweise im Förderverfahren „Innenstadt lebendig“. Dieses Rundschreiben ersetzt das Rundschreiben 07/2025 und gilt ab dem 1. Dezember 2026.',
      '1. Geltungsbereich\n\nDie folgenden Regeln gelten für alle Anträge auf Zuschüsse aus dem Programm „Innenstadt lebendig“, die nach dem 1. Dezember 2026 eingehen.',
      '2. Fristen\n\nDer Verwendungsnachweis ist spätestens sechs Monate nach Abschluss der Maßnahme vorzulegen. Eine Verlängerung um bis zu drei Monate ist auf formlosen Antrag möglich, wenn der Antrag vor Ablauf der Frist eingeht.',
      '3. Nachweise\n\nSachbericht mit Fotos, zahlenmäßiger Nachweis mit Belegliste, Kopien der Rechnungen über 1.000 Euro.',
    ].map((text, chunkIndex) => ({ chunkIndex, text, location: null, anchor: null }))
  }
  if (documentId === OFFICE_DOCUMENTS.csv.id) {
    return [
      { chunkIndex: 0, text: FRISTENLISTE_CSV.replaceAll(';', ' | '), location: 'Zeilen 1–7' },
    ].map((p) => ({ ...p, anchor: null }))
  }
  return [
    {
      chunkIndex: 0,
      text: RUECKFRAGE_EML.split('\r\n\r\n').slice(1).join('\n\n'),
      location: null,
      anchor: null,
    },
  ]
}
