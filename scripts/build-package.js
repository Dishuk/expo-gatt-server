#!/usr/bin/env node
// Compiles both targets. Sets EXPO_NONINTERACTIVE to prevent watch mode in lifecycle scripts.
// --clean removes output directories first (required for publish to avoid stale files).
const { spawnSync } = require('node:child_process');
const path = require('node:path');

const clean = process.argv.includes('--clean');

const commands = clean
  ? [['clean'], ['clean', 'plugin'], ['build'], ['build', 'plugin']]
  : [['build'], ['build', 'plugin']];

// Spawn via require.resolve (not node_modules/.bin shim, which fails on Windows and in workspaces).
// Access executable through package.json's bin field, not direct require (exports map blocks it).
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
