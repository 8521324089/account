import React, { useState } from 'react';
import { updateMyProfile } from '../api';

export default function Profile({ user, onUpdate }) {
  const [form, setForm] = useState({ phone: user.phone || '', upiId: user.upiId || '' });
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setSuccess('');
    try {
      const updated = await updateMyProfile(form);
      onUpdate(updated);
      setSuccess('Profile updated');
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <>
      <div className="card">
        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', marginBottom: '1rem' }}>
          {user.pictureUrl && (
            <img src={user.pictureUrl} alt="" style={{ width: 64, height: 64, borderRadius: '50%' }} referrerPolicy="no-referrer" />
          )}
          <div>
            <h2 style={{ marginBottom: '0.2rem' }}>{user.name}</h2>
            <p style={{ color: '#777', fontSize: '0.9rem' }}>{user.email}</p>
          </div>
        </div>
      </div>

      <div className="card">
        <h2>Edit Profile</h2>
        {error && <div className="error">{error}</div>}
        {success && <div className="success">{success}</div>}
        <form onSubmit={handleSubmit}>
          <label>Phone</label>
          <input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} placeholder="Your phone number" />
          <label>UPI ID</label>
          <input value={form.upiId} onChange={(e) => setForm({ ...form, upiId: e.target.value })} placeholder="your@upi" />
          <button className="btn-primary">Save</button>
        </form>
        <p style={{ marginTop: '1rem', fontSize: '0.8rem', color: '#999' }}>
          Name and email are managed by your Google account.
        </p>
      </div>
    </>
  );
}
