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
  sessionExpired: () => void;
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
    // Also drop the stored display name: AuthView auto-resolves via device
    // fingerprint whenever it finds one, which would instantly log the user
    // back in after an explicit logout.
    localStorage.removeItem('displayName');
    set({
      user: null,
      jwtToken: null,
      isAuthenticated: false,
    });
  },

  // The server refused our token: expired, or signed with a secret this server does not
  // have. Distinct from logout() in exactly one way, and it is the point of having both --
  // the stored displayName SURVIVES, so AuthView's auto-resolve mints a fresh token without
  // the user typing anything. Losing a session they never chose to end should not cost them
  // their name. Clearing the token is what routes them there: RequireAuth sends an
  // unauthenticated user to /login with a redirect back to where they were.
  sessionExpired: () => {
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
