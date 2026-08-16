import React from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { AuthProvider } from './contexts/AuthContext';
import { StompProvider } from './contexts/StompContext';
import { useAuthStore } from './stores/authStore';
import AuthView from './views/AuthView';
import MainView from './views/MainView';
import './App.css';

function RequireAuth({ children }: { children: JSX.Element }) {
  const { isAuthenticated } = useAuthStore();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to={`/login?redirect=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  }

  return children;
}

function AppContent() {
  const { isAuthenticated } = useAuthStore();

  return (
    <Routes>
      <Route
        path="/login"
        element={isAuthenticated ? <Navigate to="/home" replace /> : <AuthView />}
      />
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
