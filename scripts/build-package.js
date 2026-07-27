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

/**
 * The package's own entry point, run under this Node rather than through the shim in `node_modules/.bin`.
 *
 * That shim is a path this script cannot rely on. It is extensionless — on Windows npm writes
 * `expo-module.cmd` and `expo-module.ps1` beside it, and `CreateProcess` cannot execute the shell script
 * the bare name points at, so `spawnSync` failed and every `npm install` and `npm publish` on Windows
 * aborted in `prepare`. It is also assumed to sit in *this* package's `node_modules`, which is not where
 * a workspace or a hoisting layout puts it. `require.resolve` finds the package wherever the resolver
 * says it is, and spawning `process.execPath` needs no shell, no PATH and no PATHEXT.
 *
 * Resolved through `package.json` and its own `bin` field rather than by requiring the entry directly:
 * `expo-module-scripts` declares an `exports` map that does not expose the executable, so asking for the
 * subpath fails with `ERR_PACKAGE_PATH_NOT_EXPORTED`. `package.json` is exported, which is enough to
 * locate the package and read where it says its executable lives.
 */
const expoModuleManifest = require.resolve('expo-module-scripts/package.json');
const expoModule = path.join(
  path.dirname(expoModuleManifest),
  require(expoModuleManifest).bin['expo-module'],
);

for (const args of commands) {
  const result = spawnSync(process.execPath, [expoModule, ...args], {
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
