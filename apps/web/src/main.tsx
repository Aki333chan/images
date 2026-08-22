import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
/*
 * Inter — шрифт дизайн-системы, подключён СВОИМИ файлами, а не с Google
 * Fonts. Панель живёт за nginx с собственной Content-Security-Policy, и
 * внешний запрос за шрифтом означал бы ещё один домен в connect-src и
 * font-src — то есть правку конфига живого сервера ради начертания.
 * Пакет отдаёт кириллицу отдельной подвыборкой, браузер тянет только её.
 */
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import './index.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
