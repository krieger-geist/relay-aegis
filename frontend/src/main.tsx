import { StrictMode } from 'react';
import ReactDOM from 'react-dom/client';
import '@fontsource-variable/space-grotesk';
import '@fontsource-variable/ibm-plex-sans';
import '@fontsource/ibm-plex-mono/400.css';
import '@fontsource/ibm-plex-mono/500.css';
import '@fontsource/ibm-plex-mono/600.css';
import '@/styles/globals.css';
import App from '@/app/App';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <StrictMode><App /></StrictMode>,
);
