const preset = require('expo-module-scripts/jest-preset');

// Run only Android project. Tests cover all Platform.OS branches via explicit mocks,
// making duplicate runs across platforms unnecessary. No web implementation here.
const project = preset.projects.find((candidate) => candidate.displayName.name === 'Android');
if (!project) {
  // Explicit check catches project list changes and reports what exists.
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
      // Include plugin/src tests (preset roots at src alone).
      roots: ['<rootDir>/src', '<rootDir>/plugin/src'],
    },
  ],
};
