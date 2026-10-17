/** Cloud-only keyless smoke through the shipped dsh launcher, not an inline Cordis app. */
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { execFileSync, spawn } from 'node:child_process'
import { mkdirSync, cpSync, readFileSync } from 'node:fs'
import { setTimeout as delay } from 'node:timers/promises'

const require = createRequire('/opt/dsh-mobile/install/package.json')
assert.equal(process.version, 'v24.11.0')
assert.match(execFileSync('/usr/local/bin/pnpm', ['--version'], { encoding: 'utf8' }), /^11\.28\.5\s*$/)
const git = execFileSync('git', ['--version'], { encoding: 'utf8' }).match(/(\d+)\.(\d+)/)
assert.ok(git && (Number(git[1]) > 2 || Number(git[1]) === 2 && Number(git[2]) >= 45))
const ptyRequire = createRequire(require.resolve('@deepseek-ai/dsh-subprocess-local/package.json'))
const pty = ptyRequire('node-pty')
await new Promise((resolve, reject) => {
  const terminal = pty.spawn('/bin/bash', ['-c', 'printf DSH_PTY_OK'], { cols: 80, rows: 24, env: { PATH: '/usr/local/bin:/usr/bin:/bin', TERM: 'xterm' } })
  let output = ''
  const timeout = setTimeout(() => { terminal.kill(); reject(new Error('PTY timeout')) }, 10000)
  terminal.onData(data => { output += data })
  terminal.onExit(({ exitCode }) => { clearTimeout(timeout); exitCode === 0 && output.includes('DSH_PTY_OK') ? resolve() : reject(new Error('PTY failed')) })
})
const sharpRequire = createRequire(require.resolve('@deepseek-ai/dsh-attachment-local/package.json'))
const sharp = sharpRequire('sharp')
assert.ok((await sharp({ create: { width: 2, height: 2, channels: 3, background: '#abcdef' } }).png().toBuffer()).length > 0)
const profile = `${process.env.DSH_HOME}/profiles/mobile`
mkdirSync(profile, { recursive: true })
cpSync('/opt/dsh-mobile/profile-seed', profile, { recursive: true })
const config = execFileSync('/usr/local/bin/dsh', ['--profile', 'mobile', '--dump-config'], { encoding: 'utf8', timeout: 60000, maxBuffer: 8 * 1024 * 1024 })
assert.ok(config.includes('preset-mobile') && config.includes('mobile-activity'))
for (const forbidden of ['dsh-office-to-pdf', 'dsh-schedule', 'dsh-tool-goal', 'dsh-workflow-ptc', 'dsh-mcp-resources']) assert.ok(!config.includes(forbidden), forbidden)
const child = spawn('/opt/dsh-mobile/start-mobile.sh', ['3089'], { stdio: ['ignore', 'pipe', 'pipe'], detached: true })
let diagnostics = ''
child.stdout.on('data', data => { diagnostics = (diagnostics + data.toString()).slice(-16384) })
child.stderr.on('data', data => { diagnostics = (diagnostics + data.toString()).slice(-16384) })
let exited = false
const done = new Promise(resolve => child.once('exit', () => { exited = true; resolve() }))
try {
  let ready = false
  for (let i = 0; i < 90; i++) {
    if (exited) throw new Error('mobile profile exited before readiness')
    if (/dsh web: http:\/\/127\.0\.0\.1:3089\//.test(diagnostics)) { ready = true; break }
    await delay(1000)
  }
  assert.ok(ready, 'mobile boot readiness timeout')
  // Directory availability is not model-directory/API capability validation.
  assert.ok(readFileSync('/opt/dsh-mobile/mobile-bundle/selection.json', 'utf8').includes('mobile'))
  const evidence = JSON.parse(readFileSync('/tmp/dsh-mobile-activity.json', 'utf8'))
  assert.equal(evidence.coverageComplete, false)
  assert.equal(evidence.freezeEligible, false)
  console.log('PASS node/git/pnpm/PTY/sharp/mobile-profile/keyless-boot; device and provider tests NOT RUN')
} catch (error) {
  // Boot output contains a one-time browser credential: never echo it to CI logs.
  throw new Error(`mobile smoke failed: ${error.message}; ${diagnostics.replace(/https?:\/\/[^\s]+/g, '[URL redacted]').replace(/token=[^\s]+/g, 'token=[redacted]')}`)
} finally {
  if (!exited) process.kill(-child.pid, 'SIGTERM')
  await Promise.race([done, delay(10000).then(() => { if (!exited) process.kill(-child.pid, 'SIGKILL') })])
  await done
}
