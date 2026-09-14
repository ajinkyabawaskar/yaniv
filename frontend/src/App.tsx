import React from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate, useLocation, useSearchParams } from 'react-router-dom';
import { AuthProvider } from './contexts/AuthContext';
import { StompProvider } from './contexts/StompContext';
import { useAuthStore } from './stores/authStore';
import AuthView from './views/AuthView';
import MainView from './views/MainView';
import RulesView from './views/RulesView';
import UpdateBanner from './components/UpdateBanner';
import { useBackendVersionCheck } from './hooks/useBackendVersionCheck';
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
  const { updateAvailable, backendVersion, snooze } = useBackendVersionCheck();

  return (
    <>
      {updateAvailable && <UpdateBanner backendVersion={backendVersion} onLater={snooze} />}
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
      {/* Public: the rules are worth being able to link to someone who has no account yet */}
      <Route path="/rules" element={<RulesView />} />
      <Route path="/" element={<Navigate to="/home" replace />} />
      <Route path="*" element={<Navigate to="/home" replace />} />
    </Routes>
    </>
  );
}

export default function App() {
  // Card art preload happens once the player is signed in (MainView), not here:
  // the login screen has no cards to show and shouldn't spend 54 image fetches
  // (or 54 <link rel=preload> head entries) on every visitor's load.
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
