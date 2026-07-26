import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The names in `ExpoGattServerModule.ts` are the only thing tying the TypeScript layer to the native
 * modules, and nothing was checking them.
 *
 * Both integration jobs compile native code against Expo; neither reads the TypeScript declaration. So
 * a method renamed in both native files — or an event added to one platform and not the other — passed
 * every job and every suite, and broke at runtime in a consumer's app with `undefined is not a
 * function`. Expo resolves these by string at call time, which is why nothing earlier could catch it.
 *
 * Read from the sources rather than from a hand-kept list, so the check cannot drift from either side.
 */
const repoRoot = join(__dirname, '..', '..');

function read(...segments: string[]): string {
  return readFileSync(join(repoRoot, ...segments), 'utf8');
}

/** `AsyncFunction("name")` and `Function("name")`, which is how both platforms spell a method. */
function nativeMethodNames(source: string): Set<string> {
  return new Set(
    Array.from(source.matchAll(/\b(?:Async)?Function\(\s*"([A-Za-z0-9_]+)"/g), (m) => m[1]!),
  );
}

/** The single `Events("a", "b", …)` declaration each module carries. */
function nativeEventNames(source: string): Set<string> {
  const declaration = source.match(/\bEvents\(([^)]*)\)/s);
  if (!declaration) throw new Error('no Events(...) declaration found');
  return new Set(Array.from(declaration[1]!.matchAll(/"([A-Za-z0-9_]+)"/g), (m) => m[1]!));
}

/** The method names the shared declaration promises the native module exposes. */
function declaredMethodNames(): Set<string> {
  const source = read('src', 'ExpoGattServerModule.ts');
  const body = source.slice(
    source.indexOf('declare class ExpoGattServerModuleType'),
    source.indexOf('export type { ExpoGattServerModuleType }'),
  );
  return new Set(Array.from(body.matchAll(/^\s{2}([a-zA-Z]+)\s*\(/gm), (m) => m[1]!));
}

/** The event names the shared event map declares. */
function declaredEventNames(): Set<string> {
  const source = read('src', 'ExpoGattServer.types.ts');
  const start = source.indexOf('export type GattServerEvents');
  const body = source.slice(start, source.indexOf('\n};', start));
  return new Set(Array.from(body.matchAll(/^\s{2}(on[A-Za-z]+)\s*\(/gm), (m) => m[1]!));
}

const ios = read('ios', 'ExpoGattServerModule.swift');
const android = read(
  'android',
  'src',
  'main',
  'java',
  'expo',
  'modules',
  'gattserver',
  'ExpoGattServerModule.kt',
);

const sorted = (values: Set<string>) => Array.from(values).sort();

describe('the native module surface', () => {
  // Guards the extraction itself: a regex that silently matched nothing would make every comparison
  // below trivially true.
  it('finds a method and event surface on both platforms', () => {
    expect(nativeMethodNames(ios).size).toBeGreaterThan(5);
    expect(nativeMethodNames(android).size).toBeGreaterThan(5);
    expect(nativeEventNames(ios).size).toBeGreaterThan(5);
    expect(nativeEventNames(android).size).toBeGreaterThan(5);
    expect(declaredMethodNames().size).toBeGreaterThan(5);
    expect(declaredEventNames().size).toBeGreaterThan(5);
  });

  it('exposes the same methods on both platforms', () => {
    expect(sorted(nativeMethodNames(ios))).toEqual(sorted(nativeMethodNames(android)));
  });

  it('emits the same events on both platforms', () => {
    expect(sorted(nativeEventNames(ios))).toEqual(sorted(nativeEventNames(android)));
  });

  /**
   * Every method the declaration promises has to exist natively, or the call resolves to `undefined`.
   * The reverse is deliberately not asserted: a native method the TypeScript layer does not expose is
   * unreachable rather than broken.
   */
  it('declares only methods both platforms implement', () => {
    const declared = declaredMethodNames();
    expect(sorted(declared)).toEqual(
      sorted(new Set(Array.from(declared).filter((name) => nativeMethodNames(ios).has(name)))),
    );
    expect(sorted(declared)).toEqual(
      sorted(new Set(Array.from(declared).filter((name) => nativeMethodNames(android).has(name)))),
    );
  });

  /** An event declared but never emitted is a listener that silently never fires. */
  it('declares exactly the events both platforms emit', () => {
    expect(sorted(declaredEventNames())).toEqual(sorted(nativeEventNames(ios)));
    expect(sorted(declaredEventNames())).toEqual(sorted(nativeEventNames(android)));
  });
});
