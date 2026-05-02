import React, { useState, useEffect, useCallback } from 'react';
import { getMyPersons, createPerson, updatePerson, deletePerson, getGoogleContacts } from '../api';

export default function Persons({ user }) {
  const [persons, setPersons] = useState([]);
  const [form, setForm] = useState({ name: '', phone: '' });
  const [editId, setEditId] = useState(null);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [loading, setLoading] = useState(true);
  const [googleContacts, setGoogleContacts] = useState([]);
  const [showContacts, setShowContacts] = useState(false);
  const [loadingContacts, setLoadingContacts] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getMyPersons();
      setPersons(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const clearMsg = () => { setError(''); setSuccess(''); };

  const handleSubmit = async (e) => {
    e.preventDefault();
    clearMsg();
    try {
      if (editId) {
        await updatePerson(editId, { name: form.name, phone: form.phone });
        setSuccess('Person updated');
      } else {
        await createPerson({ name: form.name, phone: form.phone });
        setSuccess('Person created');
      }
      setForm({ name: '', phone: '' });
      setEditId(null);
      await load();
    } catch (err) {
      setError(err.message);
    }
  };

  const handleEdit = (p) => {
    setEditId(p.id);
    setForm({ name: p.name, phone: p.phone || '' });
    clearMsg();
  };

  const handleDelete = async (id) => {
    if (!window.confirm('Delete this person and all their transactions?')) return;
    clearMsg();
    try {
      await deletePerson(id);
      setSuccess('Person deleted');
      await load();
    } catch (err) {
      setError(err.message);
    }
  };

  const handleCancel = () => {
    setEditId(null);
    setForm({ name: '', phone: '' });
  };

  const handleImportContacts = async () => {
    setLoadingContacts(true);
    clearMsg();
    try {
      const contacts = await getGoogleContacts();
      setGoogleContacts(contacts);
      setShowContacts(true);
    } catch (err) {
      setError('Failed to load Google Contacts: ' + err.message);
    } finally {
      setLoadingContacts(false);
    }
  };

  const handleAddFromGoogle = async (contact) => {
    clearMsg();
    try {
      await createPerson({ name: contact.name, phone: contact.phone || '' });
      setSuccess(`Added ${contact.name}`);
      await load();
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <>
      <div className="card">
        <h2>{editId ? 'Edit Person' : 'Add Person'}</h2>
        {error && <div className="error">{error}</div>}
        {success && <div className="success">{success}</div>}
        <form onSubmit={handleSubmit}>
          <div className="row">
            <div>
              <label>Name</label>
              <input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>
            <div>
              <label>Phone</label>
              <input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} placeholder="Optional" />
            </div>
          </div>
          <div className="btn-row">
            <button className="btn-primary">{editId ? 'Update' : 'Add'}</button>
            {editId && <button type="button" className="btn-danger" onClick={handleCancel}>Cancel</button>}
            {!editId && (
              <button type="button" className="btn-google btn-sm" onClick={handleImportContacts} disabled={loadingContacts}>
                {loadingContacts ? 'Loading...' : '📇 Import from Google'}
              </button>
            )}
          </div>
        </form>
      </div>

      {showContacts && googleContacts.length > 0 && (
        <div className="card">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <h2>Google Contacts ({googleContacts.length})</h2>
            <button className="btn-danger btn-sm" onClick={() => setShowContacts(false)}>Close</button>
          </div>
          <table>
            <thead><tr><th>Name</th><th>Phone</th><th></th></tr></thead>
            <tbody>
              {googleContacts.map((c, i) => (
                <tr key={i}>
                  <td>{c.name}</td>
                  <td>{c.phone || '-'}</td>
                  <td><button className="btn-primary btn-sm" onClick={() => handleAddFromGoogle(c)}>Add</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="card">
        <h2>Your Contacts ({persons.length})</h2>
        {loading ? (
          <div className="empty">Loading...</div>
        ) : persons.length === 0 ? (
          <div className="empty">No contacts yet. Add one above or import from Google.</div>
        ) : (
          <table>
            <thead><tr><th>ID</th><th>Name</th><th>Phone</th><th>Actions</th></tr></thead>
            <tbody>
              {persons.map(p => (
                <tr key={p.id}>
                  <td>{p.id}</td>
                  <td>{p.name}</td>
                  <td>{p.phone || '-'}</td>
                  <td>
                    <div className="btn-row">
                      <button className="btn-primary btn-sm" onClick={() => handleEdit(p)}>Edit</button>
                      <button className="btn-danger btn-sm" onClick={() => handleDelete(p.id)}>Delete</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
