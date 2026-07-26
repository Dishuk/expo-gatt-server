const preset = require('expo-module-scripts/jest-preset');

/**
 * The `expo-module-scripts` preset runs every suite once per platform. This package uses **one** of
 * those projects, deliberately.
 *
 * The TypeScript layer is platform-independent apart from three branches on `Platform.OS`, and those
 * are covered by mocking `Platform` directly — see `platformBranching.test.ts` and
 * `unsupportedWeb.test.ts`, which assert *both* sides of each branch in a single run. Running the whole
 * suite a second time under a different `Platform.OS` re-executed a few hundred identical assertions to
 * reach one conditional, and each run only ever saw half of it.
 *
 * The web and node projects are dropped for a further reason: this package declares no web
 * implementation, and both resolve `Platform` through `react-native-web`, which it has no reason to
 * depend on.
 */
const project = preset.projects.find((candidate) => candidate.displayName.name === 'Android');

module.exports = {
  ...preset,
  projects: [{ ...project, displayName: { ...project.displayName, name: 'expo-gatt-server' } }],
};
