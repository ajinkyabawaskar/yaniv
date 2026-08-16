import React, { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '../stores/authStore';
import { userApi } from '../utils/api';
import { getPersistentFingerprint } from '../utils/fingerprint';
import './AuthView.css';

export default function AuthView() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const redirectPath = searchParams.get('redirect') || '/home';

  const { login, setLoading, setError, isLoading, error } = useAuthStore();
  const [displayName, setDisplayName] = useState('');
  const [isAutoResolving, setIsAutoResolving] = useState(true);

  useEffect(() => {
    // Attempt zero-friction auto-resolution if stored displayName exists
    const storedName = localStorage.getItem('displayName');
    if (storedName) {
      setDisplayName(storedName);
      autoLogin(storedName);
    } else {
      setIsAutoResolving(false);
    }
  }, []);

  const autoLogin = async (name: string) => {
    try {
      setIsAutoResolving(true);
      const fingerprintHash = await getPersistentFingerprint();
      const response = await userApi.resolve(fingerprintHash, name.trim());
      login(response, response.jwtToken);
      navigate(redirectPath);
    } catch (err) {
      // Fall back to manual input if auto-login fails
      setIsAutoResolving(false);
    }
  };

  const handleContinue = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!displayName.trim()) {
      setError('Please enter a display name to enter the tables');
      return;
    }

    try {
      setLoading(true);
      setError(null);

      const fingerprintHash = await getPersistentFingerprint();
      const response = await userApi.resolve(fingerprintHash, displayName.trim());

      localStorage.setItem('displayName', displayName.trim());
      login(response, response.jwtToken);
      navigate(redirectPath);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to authenticate');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-view-root">
      <div className="auth-card-wrapper">
        <div className="auth-emblem">♠ ♥ ♦ ♣</div>
        <h1 className="auth-brand-title">YANIV</h1>
        <p className="auth-brand-subtitle">High-Stakes Real-Time Card Lounge</p>

        {error && <div className="auth-error-banner">{error}</div>}

        {isAutoResolving ? (
          <div className="auto-resolve-loading">
            <div className="auth-spinner" />
            <p>Resolving player identity...</p>
          </div>
        ) : (
          <form onSubmit={handleContinue} className="auth-card-form">
            <div className="form-field-group">
              <label htmlFor="displayName">Choose Your Display Name</label>
              <input
                id="displayName"
                type="text"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                placeholder="e.g. CardShark99"
                disabled={isLoading}
                autoFocus
                maxLength={30}
              />
            </div>

            <button type="submit" disabled={isLoading || !displayName.trim()} className="auth-submit-btn">
              {isLoading ? 'Entering Table...' : 'Enter Lounge →'}
            </button>
          </form>
        )}

        <div className="auth-trust-notice">
          <span>🔒 Passwordless device fingerprinting — Zero friction onboarding</span>
        </div>
      </div>
    </div>
  );
}
