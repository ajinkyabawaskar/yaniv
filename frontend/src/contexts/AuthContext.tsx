import React, { createContext, useContext, useEffect } from 'react';
import { useAuthStore } from '../stores/authStore';
import { fingerprint } from '../utils/fingerprint';

interface AuthContextType {
  loading: boolean;
  error: string | null;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const { login, setLoading, setError, isAuthenticated } = useAuthStore();
  const [loading, setLoadingLocal] = React.useState(true);
  const [error, setErrorLocal] = React.useState<string | null>(null);

  useEffect(() => {
    // Check if user is already authenticated
    const token = localStorage.getItem('jwtToken');
    const userId = localStorage.getItem('userId');

    if (token && userId && !isAuthenticated) {
      // Restore from localStorage
      const storedUser = localStorage.getItem('user');
      if (storedUser) {
        const userData = JSON.parse(storedUser);
        login(userData, token);
      }
    }

    setLoadingLocal(false);
  }, []);

  return (
    <AuthContext.Provider value={{ loading, error: error }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuthContext() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuthContext must be used within AuthProvider');
  }
  return context;
}
