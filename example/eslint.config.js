// The example is linted by CI as well as type-checked, because it is the only consumer-facing usage of
// the public API and the guides point readers at it.
//
// It needs a configuration of its own because the root one is written for the package's own sources:
// `metro.config.js` and `babel.config.js` are CommonJS modules Node loads directly, so they use
// `require`, `module` and `__dirname`, none of which those sources ever do.
const { defineConfig } = require('eslint/config');
const baseConfig = require('expo-module-scripts/eslint.config.base');

module.exports = defineConfig([
  baseConfig,
  {
    files: ['*.config.js'],
    languageOptions: {
      sourceType: 'commonjs',
      globals: {
        __dirname: 'readonly',
        module: 'writable',
        require: 'readonly',
      },
    },
  },
]);
