import React from 'react';
import { NavLink } from 'react-router-dom';

export default function Navbar({ user, onLogout }) {
  return (
    <nav>
      <span className="brand">Ledger App</span>
      <NavLink to="/" className={({ isActive }) => isActive ? 'active' : ''} end>Dashboard</NavLink>
      <NavLink to="/persons" className={({ isActive }) => isActive ? 'active' : ''}>Persons</NavLink>
      <NavLink to="/transactions" className={({ isActive }) => isActive ? 'active' : ''}>Transactions</NavLink>
      <NavLink to="/profile" className={({ isActive }) => isActive ? 'active' : ''}>Profile</NavLink>
      <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
        {user.pictureUrl && (
          <img src={user.pictureUrl} alt="" style={{ width: 28, height: 28, borderRadius: '50%' }} referrerPolicy="no-referrer" />
        )}
        <span className="user-info">{user.name}</span>
        <button className="btn-danger btn-sm" onClick={onLogout}>Logout</button>
      </div>
    </nav>
  );
}
