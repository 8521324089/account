import React, { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { getMe } from '../api';

export default function OAuthCallback({ onAuth }) {
  const navigate = useNavigate();

  useEffect(() => {
    const fetchUser = async () => {
      try {
        const user = await getMe();
        if (user) onAuth(user);
        navigate('/');
      } catch {
        navigate('/login');
      }
    };
    fetchUser();
  }, [onAuth, navigate]);

  return <div className="login-page"><div className="card empty">Signing you in...</div></div>;
}
