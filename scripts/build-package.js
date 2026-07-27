#!/usr/bin/env node
/**
 * Compiles both published targets — `build/` from `src/`, and `plugin/build/` from `plugin/src/`.
 *
 * Exists because `expo-module build` decides on its own whether to watch: it appends `--watch` whenever
 * `process.stdout.isTTY && !process.env.CI && !process.env.EXPO_NONINTERACTIVE`. That is right for a
 * developer running `npm run build`, and wrong for a lifecycle script, which npm runs with the terminal
 * still attached — so `npm install` and `npm publish` from a terminal parked in a tsc watcher and never
 * returned. Continuous integration never saw it, because Actions sets `CI`.
 *
 * `expo-module prepublishOnly` sets `EXPO_NONINTERACTIVE` inside its own process, which covers the
 * children it spawns itself but not a sibling command in the same npm script. Setting it here covers
 * every target, and doing it in Node rather than inline in `package.json` keeps the scripts working on a
 * shell that does not understand `VAR=value command`.
 *
 * `--clean` additionally removes both output directories first, which publishing wants and a plain
 * install does not: `tsc --build` emits over the previous output but never deletes what a renamed source
 * file left behind, so a published tarball could otherwise carry a module that no longer exists.
 */
const { spawnSync } = require('node:child_process');
const path = require('node:path');

const clean = process.argv.includes('--clean');

const commands = clean
  ? [['clean'], ['clean', 'plugin'], ['build'], ['build', 'plugin']]
  : [['build'], ['build', 'plugin']];

const expoModule = path.join(__dirname, '..', 'node_modules', '.bin', 'expo-module');

for (const args of commands) {
  const result = spawnSync(expoModule, args, {
    stdio: 'inherit',
    env: { ...process.env, EXPO_NONINTERACTIVE: '1' },
  });
  if (result.error) {
    console.error(`expo-module ${args.join(' ')} could not be started: ${result.error.message}`);
    process.exit(1);
  }
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}
