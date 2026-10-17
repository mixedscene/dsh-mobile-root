/** Compile the repository-owned mobile composition; run only in the cloud builder. */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { resolve, join } from 'node:path'
import { createRequire } from 'node:module'

const [source, destination, installation] = process.argv.slice(2)
if (!source || !destination || !installation) throw new Error('usage: generate-mobile.mjs SOURCE DEST INSTALLATION')
const require = createRequire(join(resolve(installation), 'package.json'))
const yaml = require('js-yaml')
class Expression { constructor(value) { this.value = value } }
const schema = yaml.DEFAULT_SCHEMA.extend(new yaml.Type('tag:yaml.org,2002:js', {
  kind: 'scalar', instanceOf: Expression,
  construct: value => new Expression(value), represent: value => value.value,
}))
const load = path => yaml.load(readFileSync(join(source, path), 'utf8'), { schema })
const rows = new Map()
for (const path of ['packages/bundle/base/cordis.patch.yml', 'packages/bundle/web-app/cordis.patch.yml']) {
  for (const patch of load(path)) {
    if (patch.insert) for (const entry of patch.insert) rows.set(entry.id, entry)
    else {
      if (!rows.has(patch.id)) throw new Error(`unknown upstream row ${patch.id}`)
      rows.set(patch.id, { ...rows.get(patch.id), ...patch })
    }
  }
}
// Do not merely hide excluded features: their rows never enter this bundle.
const excluded = new Set([
  'hmr', 'client-hmr', 'desktop-product-telemetry', 'product-analytics', 'otel', 'session-telemetry-otel',
  'goal', 'goal-round-driver', 'command-goal', 'tool-goal', 'ui-goal',
  'schedule', 'tool-schedule', 'ui-schedule', 'ptc-runtime', 'workflow-ptc', 'tool-workflow', 'ui-workflow-run',
  'mcp-resources', 'office-to-pdf', 'ui-sidebar-documentpreview', 'ui-sidebar-browser',
  'open-in-app', 'ui-open-in-app', 'cordis-host-runner', 'cordis-client-runner', 'cordis-inspect-providers', 'ui-cordis',
  'tool-pwsh', 'pwsh-sandbox', 'web-search-deepseek',
])
for (const id of excluded) rows.delete(id)
rows.get('session-query-sqlite').config = { path: new Expression("dshHomePath('query', 'sessions.sqlite')"), openAt: 'first-search' }
rows.get('agent-preset-registry').config = { default: 'mobile' }
const preset = load('packages/bundle/web-app/presets/standard.patch.yml')[0].insert[0]
preset.id = 'preset-mobile'
preset.config.id = 'mobile'
function filter(entries) {
  return entries.filter(entry => !excluded.has(entry.id)).map(entry => {
    if (entry.group && Array.isArray(entry.config)) entry.config = filter(entry.config)
    return entry
  })
}
preset.config.plugins = filter(preset.config.plugins)
rows.set(preset.id, preset)
rows.set('mobile-terminals', { id: 'mobile-terminals', name: '@deepseek-ai/dsh-terminal' })
rows.set('mobile-terminal-bash', { id: 'mobile-terminal-bash', name: '@deepseek-ai/dsh-terminal-bash' })
rows.set('mobile-activity', { id: 'mobile-activity', name: '/opt/dsh-mobile/activity.mjs' })
preset.config.plugins.push({ id: 'tool-terminal', name: '@deepseek-ai/dsh-tool-terminal' })
const dependencies = {}
function collect(entries) {
  for (const entry of entries) {
    if (entry.name?.startsWith('@deepseek-ai/')) {
      const packageName = entry.name.split('/').slice(0, 2).join('/')
      dependencies[packageName] = packageName.startsWith('@deepseek-ai/dsh-') ? '0.2.1-alpha.2' : require(`${packageName}/package.json`).version
    }
    if (entry.group && Array.isArray(entry.config)) collect(entry.config)
    if (entry.config?.plugins) collect(entry.config.plugins)
  }
}
collect([...rows.values()])
mkdirSync(destination, { recursive: true })
writeFileSync(join(destination, 'cordis.patch.yml'), yaml.dump([{ insert: [...rows.values()] }], { schema, lineWidth: -1, noRefs: true }))
writeFileSync(join(destination, 'package.json'), JSON.stringify({
  name: '@dsh-mobile/runtime-bundle', version: '0.1.0', private: true, type: 'module', license: 'MIT',
  exports: { './package.json': './package.json' },
  dsh: { bundle: { patch: './cordis.patch.yml' } }, dependencies,
}, null, 2) + '\n')
writeFileSync(join(destination, 'selection.json'), JSON.stringify({ profile: 'mobile', excludedRows: [...excluded], mountedRows: [...rows.keys()] }, null, 2) + '\n')
