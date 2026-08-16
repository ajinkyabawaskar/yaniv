import { create } from 'zustand';

export interface User {
  userId: string;
  displayName: string;
  friendCode: string;
  createdAt: string;
}

export interface AuthState {
  user: User | null;
  jwtToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;

  // Actions
  login: (user: User, token: string) => void;
  logout: () => void;
  setLoading: (loading: boolean) => void;
  setError: (error: string | null) => void;
  updateUser: (user: Partial<User>) => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  jwtToken: null,
  isAuthenticated: false,
  isLoading: false,
  error: null,

  login: (user, token) => {
    localStorage.setItem('jwtToken', token);
    localStorage.setItem('userId', user.userId);
    localStorage.setItem('user', JSON.stringify(user));
    set({
      user,
      jwtToken: token,
      isAuthenticated: true,
    });
  },

  logout: () => {
    localStorage.removeItem('jwtToken');
    localStorage.removeItem('userId');
    localStorage.removeItem('user');
    set({
      user: null,
      jwtToken: null,
      isAuthenticated: false,
    });
  },

  setLoading: (loading) => set({ isLoading: loading }),
  setError: (error) => set({ error }),
  updateUser: (userData) =>
    set((state) => ({
      user: state.user ? { ...state.user, ...userData } : null,
    })),
}));
