// Learn more https://docs.expo.io/guides/customizing-metro
const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');

const config = getDefaultConfig(__dirname);

// npm v7+ will install ../node_modules/react and ../node_modules/react-native because of peerDependencies.
// To prevent the incompatible react-native between ./node_modules/react-native and ../node_modules/react-native,
// excludes the one from the parent folder when bundling.
config.resolver.blockList = [
  ...Array.from(config.resolver.blockList ?? []),
  // On windows the path will resolve with `\`. We need to escape it with `\\` for the RegExp.
  new RegExp(path.resolve(__dirname, '..', 'node_modules', 'react').replace(/\\/g, '\\\\')),
  new RegExp(path.resolve(__dirname, '..', 'node_modules', 'react-native').replace(/\\/g, '\\\\')),
  // Blocked for the same reason, and easy to miss: the module's own `build/index.js` imports
  // `expo-modules-core`, which resolves upward to the parent's development copy while the app's code
  // resolves its own. The two are pinned independently — the parent by a devDependency range, the app
  // by whatever `expo` brings — so the moment they drift the example runs two module runtimes, with
  // `Platform` and `requireNativeModule` coming from whichever was loaded first.
  new RegExp(
    path.resolve(__dirname, '..', 'node_modules', 'expo-modules-core').replace(/\\/g, '\\\\'),
  ),
];

config.resolver.nodeModulesPaths = [
  path.resolve(__dirname, './node_modules'),
  path.resolve(__dirname, '../node_modules'),
];

// `expo-gatt-server` is a `file:..` dependency, so npm symlinks it into ./node_modules. Watch the
// parent folder so changes to the module are picked up by Fast Refresh.
//
// What is picked up is the *build output*: the package's `main` is `build/index.js` since it started
// shipping compiled JavaScript, so editing `src/*.ts` changes nothing here until `npm run build` has
// run in the parent. Run it in watch mode — `npx expo-module build` — to get the old feel back.
config.watchFolders = [path.resolve(__dirname, '..')];

config.transformer.getTransformOptions = async () => ({
  transform: {
    experimentalImportSupport: false,
    inlineRequires: true,
  },
});

module.exports = config;
