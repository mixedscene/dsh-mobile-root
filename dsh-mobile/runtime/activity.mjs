/** Agent-only event evidence. It never grants permission to freeze the runtime. */
import { writeFileSync, renameSync } from 'node:fs'

export const name = 'mobile-activity'
export const inject = ['agents']

/** Register event-owned updates, without polling or interpreting network silence. */
export function apply(ctx) {
  const running = new Set(ctx.agents.list().filter(agent => agent.status === 'running').map(agent => agent.id))
  let generation = 0
  const publish = () => {
    const state = {
      format: 1, pid: process.pid, sequence: ++generation,
      coverageComplete: false, freezeEligible: false,
      runningAgents: running.size,
      uncovered: ['native-tasks', 'plugin-install', 'standalone-pty', 'background-jobs', 'index-transactions'],
    }
    const temporary = `/tmp/.dsh-mobile-activity-${process.pid}.json`
    writeFileSync(temporary, JSON.stringify(state) + '\n', { mode: 0o600 })
    renameSync(temporary, '/tmp/dsh-mobile-activity.json')
  }
  ctx.on('agent/status', ({ agent, status }) => {
    if (status === 'running') running.add(agent.id)
    else running.delete(agent.id)
    publish()
  })
  ctx.on('agent/disposed', ({ agent }) => { running.delete(agent.id); publish() })
  publish()
}
