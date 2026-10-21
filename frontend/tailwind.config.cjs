/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        app: 'var(--bg-app)',
        surface: 'var(--surface-1)',
        ink: 'var(--text-primary)',
        brand: 'var(--brand-600)',
        ai: 'var(--ai-600)',
        border: 'var(--border)',
      },
      fontFamily: {
        sans: ['IBM Plex Sans Variable', 'IBM Plex Sans', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        display: ['Space Grotesk Variable', 'Space Grotesk', 'ui-sans-serif', 'sans-serif'],
        mono: ['IBM Plex Mono', 'ui-monospace', 'monospace'],
      },
      borderRadius: { panel: 'var(--radius-8)' },
    },
  },
  plugins: [],
};
