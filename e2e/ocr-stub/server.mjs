// Stand-in for docling-serve (the OCR service opaa-ocr) in the E2E stack, for the text recognition
// scenarios (tests/text-recognition.spec.ts). Speaks the three routes TextRecognitionClient uses:
//
//   GET  /health                  200 without a key, like docling-serve
//   GET  /v1/status/poll/<id>     the key probe: 401 without the right X-Api-Key, else 404 (no task)
//   POST /v1/convert/file         multipart with the procedure options and one file "files"
//
// A conversion needs the key and exactly the options OPAA sends (EXPECTED_OPTIONS); otherwise it
// answers 401 or 422, so a change on either side fails loudly. The answer for the scenarios' scan
// (FIXTURE_DIR/satzung-scan.pdf, recognised by its SHA-256) is the DoclingDocument a real opaa-ocr
// run made of that file (satzung-scan.docling.json), so boxes, origins and labels are the real
// ones. Any other PDF - the connection test's own test page among them - gets a one-line document.
//
// GET /control/conversions tells a scenario how many conversions were asked for, per file kind.
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { join } from 'node:path'

const PORT = Number(process.env.PORT ?? 5001)
const API_KEY = process.env.OCR_STUB_API_KEY ?? ''
const FIXTURE_DIR = process.env.FIXTURE_DIR ?? '/fixtures'

// TextRecognitionClient.DOCLING_OPTIONS, field by field.
const EXPECTED_OPTIONS = {
  from_formats: 'pdf',
  to_formats: 'json',
  do_ocr: 'true',
  force_ocr: 'true',
  ocr_engine: 'rapidocr',
  ocr_lang: 'de',
  do_table_structure: 'true',
  table_mode: 'accurate',
  image_export_mode: 'placeholder',
  abort_on_error: 'false',
}

const scanSha256 = sha256(readFileSync(join(FIXTURE_DIR, 'satzung-scan.pdf')))
const scanDocument = JSON.parse(readFileSync(join(FIXTURE_DIR, 'satzung-scan.docling.json'), 'utf8'))

const conversions = { scan: 0, other: 0, refused: 0 }

function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex')
}

function sendJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(body))
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    req.on('data', (chunk) => chunks.push(chunk))
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

/** The parts of a multipart/form-data body: name -> Buffer. */
function multipartParts(body, contentType) {
  const boundary = /boundary=([^;]+)/.exec(contentType ?? '')?.[1]
  if (!boundary) return null
  const delimiter = Buffer.from(`--${boundary}`)
  const parts = {}
  let start = body.indexOf(delimiter)
  while (start >= 0) {
    const next = body.indexOf(delimiter, start + delimiter.length)
    if (next < 0) break
    const part = body.subarray(start + delimiter.length + 2, next - 2)
    const headerEnd = part.indexOf('\r\n\r\n')
    const name = /name="([^"]+)"/.exec(part.subarray(0, headerEnd).toString('utf8'))?.[1]
    if (name) parts[name] = part.subarray(headerEnd + 4)
    start = next
  }
  return parts
}

/** A DoclingDocument with one line of text on one A4 page, BOTTOMLEFT like docling-serve. */
function oneLineDocument() {
  return {
    schema_name: 'DoclingDocument',
    version: '1.7.0',
    name: 'document',
    body: { self_ref: '#/body', children: [{ $ref: '#/texts/0' }], content_layer: 'body', label: 'unspecified' },
    texts: [
      {
        self_ref: '#/texts/0',
        parent: { $ref: '#/body' },
        children: [],
        content_layer: 'body',
        label: 'text',
        prov: [
          {
            page_no: 1,
            bbox: { l: 72, t: 770, r: 400, b: 755, coord_origin: 'BOTTOMLEFT' },
            charspan: [0, 27],
          },
        ],
        orig: 'Testseite der Texterkennung',
        text: 'Testseite der Texterkennung',
      },
    ],
    tables: [],
    pictures: [],
    groups: [],
    key_value_items: [],
    form_items: [],
    pages: { 1: { size: { width: 595.0, height: 842.0 }, page_no: 1 } },
  }
}

function conversionAnswer(document) {
  return {
    document: { filename: 'document.pdf', json_content: document },
    status: 'success',
    errors: [],
    processing_time: 0.1,
    timings: {},
  }
}

const server = createServer(async (req, res) => {
  try {
    const path = new URL(req.url, 'http://localhost').pathname
    const keyAccepted = API_KEY !== '' && req.headers['x-api-key'] === API_KEY
    if (req.method === 'GET' && path === '/health') {
      sendJson(res, 200, { status: 'ok' })
      return
    }
    if (req.method === 'GET' && path === '/control/conversions') {
      sendJson(res, 200, conversions)
      return
    }
    if (req.method === 'GET' && path.startsWith('/v1/status/poll/')) {
      sendJson(res, keyAccepted ? 404 : 401, { detail: keyAccepted ? 'Task not found.' : 'Unauthorized' })
      return
    }
    if (req.method === 'POST' && path === '/v1/convert/file') {
      const body = await readBody(req)
      if (!keyAccepted) {
        conversions.refused += 1
        sendJson(res, 401, { detail: 'Unauthorized' })
        return
      }
      const parts = multipartParts(body, req.headers['content-type'])
      const file = parts?.files
      const wrongOptions = Object.entries(EXPECTED_OPTIONS).filter(
        ([name, value]) => parts?.[name]?.toString('utf8') !== value,
      )
      if (!file || wrongOptions.length > 0) {
        conversions.refused += 1
        sendJson(res, 422, {
          detail: `unexpected options: ${wrongOptions.map(([name]) => name).join(', ') || 'files'}`,
        })
        return
      }
      if (sha256(file) === scanSha256) {
        conversions.scan += 1
        sendJson(res, 200, conversionAnswer(scanDocument))
        return
      }
      conversions.other += 1
      sendJson(res, 200, conversionAnswer(oneLineDocument()))
      return
    }
    sendJson(res, 404, { detail: 'Not Found' })
  } catch (error) {
    sendJson(res, 500, { detail: error instanceof Error ? error.message : 'internal error' })
  }
})

server.listen(PORT, () => {
  console.log(`OCR stub listening on :${PORT}`)
})
