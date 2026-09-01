import { defineConfig } from 'vitest/config'
import type { PluginOption } from 'vite'
import vue from '@vitejs/plugin-vue'
import wasmPlugin from 'vite-plugin-wasm'

// vite-plugin-wasm's published .d.ts resolves incorrectly under this
// project's "module": "nodenext" TS config (TS picks up a namespace-typed
// declaration instead of the actual callable default export the "import"
// exports condition resolves to at runtime) -- a known dual-CJS/ESM-
// packaging type mismatch, not a real runtime issue (verified: `node -e
// "require(...)"` and the package's own exports/import.mjs both confirm the
// real export is a plain callable function). Re-typed here rather than
// disabling type-checking project-wide.
const wasm = wasmPlugin as unknown as () => PluginOption

// https://vite.dev/config/
export default defineConfig({
  // Web Sprint 2 wires vaultCrypto.ts (and therefore argon2-browser, which
  // ships a plain Emscripten-glue .wasm expecting manual JS-side
  // instantiation) into the actual app bundle for the first time via
  // Login/Register -- Sprint 1 never did, since nothing in its UI called it.
  // Without this plugin, Vite 8's default WASM/ESM-integration handling of
  // plain `.wasm` imports (new in this Vite version) misinterprets
  // argon2-browser's wasm import namespace ("a"/"b", the Emscripten
  // asmLibraryArg names) as JS module specifiers to resolve, and the dev
  // server 500s with "Failed to resolve import 'a'". vite-plugin-wasm
  // restores the classic init-based wasm handling this library expects.
  plugins: [vue(), wasm()],
  test: {
    environment: 'node',
    setupFiles: ['./vitest.setup.ts'],
    include: ['src/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/crypto/**', 'src/services/**', 'src/stores/**'],
    },
  },
})
