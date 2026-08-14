import type { Config } from 'tailwindcss';

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        border: 'hsl(217 19% 20%)',
        background: 'hsl(222 22% 10%)',
        card: 'hsl(222 20% 13%)',
        muted: 'hsl(220 14% 60%)',
        primary: { DEFAULT: 'hsl(43 74% 52%)', foreground: 'hsl(222 22% 10%)' },
        destructive: 'hsl(0 72% 51%)',
      },
    },
  },
  plugins: [],
} satisfies Config;
