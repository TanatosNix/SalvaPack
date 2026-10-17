import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Some IDEs set DEBUG globally. Avoid flooding stdout with Firebase's debug
// traces (which include the child-process environment and expected denials).
const child = spawn(process.execPath, [
  fileURLToPath(new URL('../node_modules/firebase-tools/lib/bin/firebase.js', import.meta.url)),
  'emulators:exec', '--config', '../firebase.json', '--only', 'firestore',
  '--project', 'demo-salvapack',
  'node --test --test-concurrency=1 tests/firestore.rules.test.mjs',
], {
  cwd: fileURLToPath(new URL('..', import.meta.url)),
  env: { ...process.env, DEBUG: '' },
  stdio: 'inherit',
});
child.on('error', (error) => { console.error(error.message); process.exitCode = 1; });
child.on('exit', (code) => { process.exitCode = code ?? 1; });
