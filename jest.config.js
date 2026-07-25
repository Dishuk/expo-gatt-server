const preset = require('expo-module-scripts/jest-preset');

/**
 * The `expo-module-scripts` preset runs every suite once per platform. Only the two this package
 * declares in `expo-module.config.json` are kept: there is no web implementation to test — `isSupported`
 * is documented as `false` there — and the web and node projects resolve `Platform` through
 * `react-native-web`, which this package has no reason to depend on.
 */
module.exports = {
  ...preset,
  projects: preset.projects.filter((project) => ['iOS', 'Android'].includes(project.displayName.name)),
};
