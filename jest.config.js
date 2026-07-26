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
if (!project) {
  // Named rather than left to throw on the spread below, which reports only that `displayName` is
  // undefined. The preset's project list is not a documented API, so a rename is a real possibility.
  throw new Error(
    'expo-module-scripts/jest-preset no longer exposes an "Android" project; ' +
      `saw ${preset.projects.map((candidate) => candidate.displayName?.name).join(', ')}. ` +
      'Pick a different one in jest.config.js.',
  );
}

module.exports = {
  ...preset,
  projects: [
    {
      ...project,
      displayName: { ...project.displayName, name: 'expo-gatt-server' },
      // The preset roots at `src` alone, which silently walled off `plugin/src` — a test placed there
      // would never have run, and the plugin had shipped a regression already.
      roots: ['<rootDir>/src', '<rootDir>/plugin/src'],
    },
  ],
};
