import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'
import eslintConfigPrettier from 'eslint-config-prettier'

export default tseslint.config(
  { ignores: ['dist/**', 'coverage/**', 'node_modules/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
      },
    },
    rules: {
      // Same reasoning typescript-eslint's own recommended config already
      // applies to plain .ts/.tsx files (disabling core `no-undef` there):
      // TypeScript itself (via `vue-tsc -b`, part of `npm run build`)
      // already catches genuinely undefined identifiers with full type
      // awareness, and core ESLint's no-undef doesn't know about ambient
      // browser globals (`setTimeout`/`clearTimeout`/`window`/etc.) inside
      // a `.vue` SFC's `<script setup>` block the way it apparently does
      // for `.ts` files under this config -- without this, using any
      // browser global directly in a component (e.g. this sprint's
      // clipboard-copy status timers) is a false positive, not a real bug.
      'no-undef': 'off',
    },
  },
  {
    rules: {
      // Vue SFCs commonly use single-word view/root component names
      // (App.vue, HealthCheckView.vue is fine, but keep the door open).
      'vue/multi-word-component-names': 'off',
    },
  },
  eslintConfigPrettier,
)
