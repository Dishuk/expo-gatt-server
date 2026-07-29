// Consumed only by Jest: the `expo-module-scripts` preset transforms `src` with `babel-jest` under
// the Metro caller, which needs `babel-preset-expo` to resolve `Platform.OS` per test project.
module.exports = function (api) {
  api.cache(true);
  return {
    presets: ['babel-preset-expo'],
  };
};
