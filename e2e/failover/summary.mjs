// Table of the failover test (deploy/helm/ci/failover-test.sh): one row per result file that
// e2e/failover/tests/failover.spec.ts wrote, as Markdown on stdout.
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const dir = process.argv[2] ?? 'e2e/failover-results'
const seconds = (ms) => (ms === null || ms === undefined ? '–' : (ms / 1000).toFixed(1))
const outcomes = (map) =>
  Object.entries(map)
    .map(([outcome, count]) => `${count}× ${outcome}`)
    .join(', ') || '–'

// Gaps of the invalidation bus from the logs of a run, for result files written without them.
function busGaps(run, since) {
  const gaps = []
  for (const name of readdirSync(dir).filter((file) => file.startsWith(`${run}-`) && file.endsWith('.log'))) {
    let lostAt = null
    for (const line of readFileSync(join(dir, name), 'utf8').split('\n')) {
      const at = Date.parse(line.slice(0, line.indexOf(' ')).replace(/(\.\d{3})\d*Z$/, '$1Z'))
      if (Number.isNaN(at) || at < since) continue
      if (lostAt === null && line.includes('The invalidation bus does not listen')) lostAt = at
      else if (lostAt !== null && line.includes('Listening on the invalidation bus again')) {
        gaps.push(at - lostAt)
        lostAt = null
      }
    }
  }
  return gaps
}

const rows = readdirSync(dir)
  .filter((name) => name.endsWith('.json'))
  .sort()
  .map((name) => JSON.parse(readFileSync(join(dir, name), 'utf8')))

console.log(
  '| Lauf | neuer Primärserver nach s | ohne Primärserver s | Anfragen | Fehlerantworten | erste/letzte s | längste Lücke s | Mails erwartet/zugestellt | Bus ohne Hörverbindung s (Log) | Herzschlag max. s | Pool wartend max./Timeouts | Lauf / Upload / geplant | Log: Bus weg/zurück, Herzschlag fehlgeschlagen, Instanz tot |',
)
console.log('|---|---|---|---|---|---|---|---|---|---|---|---|---|')
for (const r of rows) {
  const pods = Object.values(r.timeline.perPod)
  const logs = r.logs.counts
  console.log(
    [
      '',
      r.run,
      seconds(r.timeline.newPrimaryAtMs),
      seconds(r.timeline.withoutPrimaryMs),
      r.load.requests,
      `${r.load.failed}: ${outcomes(r.load.failedOutcomes)}`,
      `${seconds(r.load.firstFailureMs)}/${seconds(r.load.lastFailureMs)}`,
      seconds(r.load.longestGapMs),
      `${r.mails.expected}/${r.mails.delivered}`,
      (r.logs.busGapsMs ?? busGaps(r.run, Date.parse(r.startedAt) - 5000)).map(seconds).join(', ') || '–',
      Math.max(...pods.map((pod) => pod.maxHeartbeatAgeSeconds)).toFixed(0),
      `${Math.max(...pods.map((pod) => pod.maxPoolPending))}/${pods.reduce((sum, pod) => sum + pod.poolTimeouts, 0)}`,
      `${r.indexing.status} ${r.indexing.documentsIndexedTotal}/${r.indexing.expected} / ${r.upload.status} / ${r.scheduled.runs.join('+') || 'keiner'}`,
      `${logs.busLost}/${logs.busBack}, ${logs.heartbeatFailed}, ${logs.instanceLost}`,
      '',
    ].join(' | ').trim(),
  )
}
