import React, { useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate, useLocation, useSearchParams } from 'react-router-dom';
import { AuthProvider } from './contexts/AuthContext';
import { StompProvider } from './contexts/StompContext';
import { useAuthStore } from './stores/authStore';
import AuthView from './views/AuthView';
import MainView from './views/MainView';
import { preloadAllCards, preloadCardsViaLink } from './utils/cardPreload';
import './App.css';

function RequireAuth({ children }: { children: JSX.Element }) {
  const { isAuthenticated } = useAuthStore();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to={`/login?redirect=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  }

  return children;
}

function LoginRoute() {
  const { isAuthenticated } = useAuthStore();
  const [searchParams] = useSearchParams();

  // Already signed in: honor any deep-link redirect instead of dumping users on /home
  if (isAuthenticated) {
    return <Navigate to={searchParams.get('redirect') || '/home'} replace />;
  }
  return <AuthView />;
}

function AppContent() {
  const { isAuthenticated } = useAuthStore();

  return (
    <Routes>
      <Route path="/login" element={<LoginRoute />} />
      <Route
        path="/home"
        element={
          <RequireAuth>
            <MainView />
          </RequireAuth>
        }
      />
      <Route
        path="/join/:roomCode"
        element={
          <RequireAuth>
            <MainView />
          </RequireAuth>
        }
      />
      <Route path="/" element={<Navigate to="/home" replace />} />
      <Route path="*" element={<Navigate to="/home" replace />} />
    </Routes>
  );
}

export default function App() {
  // Start preloading card assets as early as possible - at app mount
  // This runs in background before user even reaches AuthView
  useEffect(() => {
    console.log('[CardPreload] Starting preload at App mount');
    // Use both methods for maximum browser coverage
    preloadAllCards().catch(() => {
      // Silently ignore - individual views will also attempt preload
    });
    preloadCardsViaLink();
  }, []);

  return (
    <Router>
      <AuthProvider>
        <StompProvider>
          <div className="app-root">
            <AppContent />
          </div>
        </StompProvider>
      </AuthProvider>
    </Router>
  );
}
