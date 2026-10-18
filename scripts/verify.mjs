import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { startupSmoke } from './startup-smoke.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const frontend = path.join(root, 'frontend')
const behaviorOnly = process.argv.includes('--behavior-only')
const startupOnly = process.argv.includes('--startup-only')
if ((behaviorOnly && startupOnly) || process.argv.slice(2).some(arg => !['--behavior-only', '--startup-only'].includes(arg))) {
  throw new Error('Usage: node scripts/verify.mjs [--behavior-only | --startup-only]')
}
const project = `governance-verify-${randomUUID()}`
const output = path.join(root, '.local', 'verification', project)
const compose = ['compose', '--env-file', path.join(root, '.env.example'), '-f', path.join(root, 'compose.verify.yml'), '-p', project]
const results = []
let started = false
let active
let interrupted = false
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    interrupted = true
    active?.kill('SIGTERM')
  })
}

function run(command, args, { cwd = root, env = {}, capture = false } = {}) {
  return new Promise((resolve, reject) => {
    // Windows npm/Maven launchers are .cmd files. Arguments here are fixed by this script.
    const windowsLauncher = process.platform === 'win32' && ['npm', 'npx', 'mvn'].includes(command)
    const child = spawn(windowsLauncher ? `${command}.cmd` : command, args, {
      cwd, env: { ...process.env, ...env }, shell: windowsLauncher,
      stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit',
      windowsHide: true,
    })
    active = child
    let stdout = ''
    let stderr = ''
    if (capture) {
      child.stdout.on('data', chunk => { stdout += chunk })
      child.stderr.on('data', chunk => { stderr += chunk })
    }
    child.on('error', reject)
    child.on('close', code => {
      if (active === child) active = undefined
      if (code === 0) resolve(stdout.trim())
      else reject(new Error(`${command} ${args.join(' ')} failed (${code})\n${stderr}`))
    })
  })
}

async function stage(name, action) {
  if (interrupted) throw new Error('Verification interrupted')
  console.log(`\n${name}`)
  try {
    await action()
    results.push({ name, status: 'passed' })
  } catch (error) {
    results.push({ name, status: 'failed', error: error.message })
    throw error
  }
}

async function waitForApplication(baseURL) {
  const { request } = await import(new URL('../frontend/node_modules/@playwright/test/index.mjs', import.meta.url))
  const deadline = Date.now() + 180_000
  while (Date.now() < deadline && !interrupted) {
    const context = await request.newContext({ baseURL, timeout: 3000 })
    try {
      const csrf = await (await context.get('/api/auth/csrf')).json()
      const login = await context.post('/api/auth/login', {
        form: { username: 'special', password: 'Demo-Only-2026!' },
        headers: { [csrf.headerName]: csrf.token },
      })
      // An anonymous CSRF response alone can arrive before the demo runners finish.
      if (login.status() === 204) {
        const sources = await context.get('/api/options/sources')
        if (sources.ok() && (await sources.json()).length > 0) return
      }
    } catch { /* Nginx can start before database migration and demo initialization finish. */ }
    finally { await context.dispose() }
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  throw new Error('Application readiness timed out or verification was interrupted')
}

await mkdir(output, { recursive: true })
try {
  // Fail before reinstalling dependencies if the required runtime is unavailable.
  await stage('Docker availability', () => run('docker', ['info'], { capture: true }))
  if (!behaviorOnly && !startupOnly) {
    await stage('Backend tests and compile/package', () => run('mvn', ['-B', 'verify'], { cwd: path.join(root, 'backend') }))
  }
  await stage('Frontend dependencies', () => run('npm', ['ci'], { cwd: frontend }))
  if (!behaviorOnly && !startupOnly) {
    await stage('Frontend typecheck', () => run('npm', ['run', 'typecheck'], { cwd: frontend }))
    await stage('Frontend production build', () => run('npx', ['vite', 'build'], { cwd: frontend }))
  }
  if (!startupOnly) await stage('Browser dependencies', () => run('npx', ['playwright', 'install', ...(process.platform === 'linux' ? ['--with-deps'] : []), 'chromium'], { cwd: frontend }))
  let baseURL
  await stage('Isolated MySQL/API/web startup', async () => {
    started = true
    await run('docker', [...compose, 'up', '-d', '--build'])
    const address = await run('docker', [...compose, 'port', 'web', '80'], { capture: true })
    baseURL = `http://${address}`
    await waitForApplication(baseURL)
  })
  let persisted
  await stage('Production assets, Nginx API proxy, login and database write', async () => {
    persisted = await startupSmoke(baseURL)
  })
  await stage('Container recreation and MySQL volume persistence', async () => {
    await writeFile(path.join(output, 'before-recreation.log'), await run('docker', [...compose, 'logs', '--no-color'], { capture: true }))
    await run('docker', [...compose, 'down'])
    await run('docker', [...compose, 'up', '-d'])
    baseURL = `http://${await run('docker', [...compose, 'port', 'web', '80'], { capture: true })}`
    await waitForApplication(baseURL)
    await startupSmoke(baseURL, persisted)
  })
  if (!startupOnly) await stage('HTTP/MySQL and browser behavior tests', () => run('npm', ['test'], {
    cwd: frontend,
    env: { BASE_URL: baseURL, DEMO_PASSWORD: 'Demo-Only-2026!', COOKIE_SECURE: 'false', PLAYWRIGHT_CHROMIUM_EXECUTABLE: '' },
  }))
} catch (error) {
  console.error(error.message)
  process.exitCode = 1
} finally {
  if (started) {
    try {
      const logs = await run('docker', [...compose, 'logs', '--no-color'], { capture: true })
      await writeFile(path.join(output, 'services.log'), logs)
    } catch (error) { console.error(`Log collection failed: ${error.message}`) }
    try {
      // Only this invocation's randomly named project and verification volume are removed.
      await run('docker', [...compose, 'down', '--volumes', '--remove-orphans'])
    } catch (error) {
      console.error(`Cleanup failed: ${error.message}`)
      process.exitCode = 1
      results.push({ name: 'Cleanup', status: 'failed', error: error.message })
    }
  }
  await writeFile(path.join(output, 'summary.json'), JSON.stringify(results, null, 2))
  console.table(results.map(({ name, status }) => ({ check: name, status })))
  console.log(`Verification logs: ${output}`)
}
