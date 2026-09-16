// Quality gate frontend: eslint su index.html (JS inline nei tag <script>)
// Run: npm ci && npm run lint
// eslint-plugin-html estrae gli script inline e li linta come JS.
import html from 'eslint-plugin-html'
import globals from 'globals'

export default [
  {
    files: ['**/*.html'],
    plugins: { html },
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'script',
      globals: {
        ...globals.browser
      }
    },
    rules: {
      // no-unused-vars a 'warn' (non blocca): le function decl sono wiring'd via
      // onclick="..."/template string che ESLint non può tracciare → falsi positivi.
      // 'no-undef' resta il gate hard contro i nomi sbagliati.
      'no-unused-vars': ['warn', { args: 'none' }],
      'no-undef': 'error',
      'no-var': 'error',
      eqeqeq: ['error', 'smart'],
      'no-eval': 'error',
      'no-alert': 'warn',
      'no-console': 'off' // SPA vanilla: i log sono l'unico logging
    }
  }
]
