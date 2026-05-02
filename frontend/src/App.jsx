import React, { useState, useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import './App.css';
import Login from './pages/Login';
import OAuthCallback from './pages/OAuthCallback';
import Dashboard from './pages/Dashboard';
import Persons from './pages/Persons';
import Transactions from './pages/Transactions';
import Profile from './pages/Profile';
import Navbar from './components/Navbar';
import { getMe } from './api';

export default function App() {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const checkAuth = async () => {
      try {
        const u = await getMe();
        if (u) setUser(u);
      } catch {
        // not authenticated
      } finally {
        setLoading(false);
      }
    };
    checkAuth();
  }, []);

  const handleLogout = async () => {
    await fetch('/api/v1/auth/logout', { method: 'POST' });
    setUser(null);
  };

  if (loading) return <div className="login-page"><div className="card empty">Loading...</div></div>;

  if (!user) {
    return (
      <BrowserRouter>
        <Routes>
          <Route path="/oauth-callback" element={<OAuthCallback onAuth={setUser} />} />
          <Route path="*" element={<Login />} />
        </Routes>
      </BrowserRouter>
    );
  }

  return (
    <BrowserRouter>
      <Navbar user={user} onLogout={handleLogout} />
      <div className="container">
        <Routes>
          <Route path="/" element={<Dashboard user={user} />} />
          <Route path="/persons" element={<Persons user={user} />} />
          <Route path="/transactions" element={<Transactions user={user} />} />
          <Route path="/profile" element={<Profile user={user} onUpdate={setUser} />} />
          <Route path="/oauth-callback" element={<Navigate to="/" />} />
          <Route path="*" element={<Navigate to="/" />} />
        </Routes>
      </div>
    </BrowserRouter>
  );
}
