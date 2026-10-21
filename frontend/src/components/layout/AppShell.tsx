import { useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { TopBar } from './TopBar';

export function AppShell() {
  const [menuOpen, setMenuOpen] = useState(false);
  return (
    <div className="app-shell">
      <Sidebar open={menuOpen} onClose={() => setMenuOpen(false)} />
      <div className="main-area">
        <TopBar onMenu={() => setMenuOpen((current) => !current)} />
        <main id="main-content" className="page-content"><Outlet /></main>
      </div>
    </div>
  );
}
