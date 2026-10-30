// Minimal stand-in for a clamd daemon for the E2E stack: speaks the INSTREAM command the backend's
// ClamdClient sends (zINSTREAM, length-prefixed chunks, a zero-length chunk ends the stream) and
// answers like clamd - FOUND for a stream containing the EICAR test string, OK for anything else.
// PING is answered for manual checks. The EICAR string is assembled at runtime so this file itself
// is never flagged by a virus scanner.
import { createServer } from 'node:net'
import { createServer as createHttpServer } from 'node:http'

const PORT = Number(process.env.PORT ?? 3310)
const HEALTH_PORT = Number(process.env.HEALTH_PORT ?? 3311)
const EICAR = Buffer.from(
  ['X5O!P%@AP[4\\PZX54(P^)7CC)7}$', 'EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*'].join(''),
  'latin1',
)

let scans = 0
let findings = 0

function handle(socket) {
  let buffer = Buffer.alloc(0)
  let command = null
  const chunks = []
  socket.on('data', (data) => {
    buffer = Buffer.concat([buffer, data])
    if (command === null) {
      const end = buffer.indexOf(0)
      if (end < 0) return
      command = buffer.subarray(0, end).toString('ascii').replace(/^[zn]/, '')
      buffer = buffer.subarray(end + 1)
      if (command === 'PING') {
        socket.end('PONG\0')
        return
      }
      if (command !== 'INSTREAM') {
        socket.end('UNKNOWN COMMAND\0')
        return
      }
    }
    while (buffer.length >= 4) {
      const length = buffer.readUInt32BE(0)
      if (length === 0) {
        scans++
        const found = Buffer.concat(chunks).includes(EICAR)
        if (found) findings++
        socket.end(found ? 'stream: Eicar-Test-Signature FOUND\0' : 'stream: OK\0')
        buffer = Buffer.alloc(0)
        return
      }
      if (buffer.length < 4 + length) return
      chunks.push(buffer.subarray(4, 4 + length))
      buffer = buffer.subarray(4 + length)
    }
  })
  socket.on('error', () => {})
}

createServer(handle).listen(PORT, () => console.log(`clamd-stub listening on ${PORT}`))

// GET /health for the Compose healthcheck, GET /__state for how many streams were judged.
createHttpServer((req, res) => {
  res.setHeader('Content-Type', 'application/json')
  res.end(JSON.stringify(req.url === '/__state' ? { scans, findings } : { status: 'ok' }))
}).listen(HEALTH_PORT)
