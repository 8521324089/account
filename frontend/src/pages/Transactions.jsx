import React, { useState, useEffect, useCallback } from 'react';
import { getMyTransactions, getMyTransactionsByType, getMyPersons, createTransaction, updateTransaction, deleteTransaction } from '../api';

export default function Transactions({ user }) {
  const [transactions, setTransactions] = useState([]);
  const [persons, setPersons] = useState([]);
  const [filter, setFilter] = useState('ALL');
  const [form, setForm] = useState({ personId: '', amount: '', type: 'GIVE', note: '' });
  const [editId, setEditId] = useState(null);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [loading, setLoading] = useState(true);

  const loadPersons = useCallback(async () => {
    try { setPersons(await getMyPersons()); } catch (err) { console.error(err); }
  }, []);

  const loadTransactions = useCallback(async () => {
    setLoading(true);
    try {
      const data = filter === 'ALL' ? await getMyTransactions() : await getMyTransactionsByType(filter);
      setTransactions(data);
    } catch (err) { setError(err.message); }
    finally { setLoading(false); }
  }, [filter]);

  useEffect(() => { loadPersons(); }, [loadPersons]);
  useEffect(() => { loadTransactions(); }, [loadTransactions]);

  const clearMsg = () => { setError(''); setSuccess(''); };

  const handleSubmit = async (e) => {
    e.preventDefault();
    clearMsg();
    try {
      const payload = { personId: Number(form.personId), amount: Number(form.amount), type: form.type, note: form.note };
      if (editId) {
        await updateTransaction(editId, payload);
        setSuccess('Transaction updated');
      } else {
        await createTransaction(payload);
        setSuccess('Transaction created');
      }
      setForm({ personId: '', amount: '', type: 'GIVE', note: '' });
      setEditId(null);
      await loadTransactions();
    } catch (err) { setError(err.message); }
  };

  const handleEdit = (t) => {
    setEditId(t.id);
    setForm({ personId: String(t.personId), amount: String(t.amount), type: t.type, note: t.note || '' });
    clearMsg();
  };

  const handleDelete = async (id) => {
    if (!window.confirm('Delete this transaction?')) return;
    clearMsg();
    try {
      await deleteTransaction(id);
      setSuccess('Transaction deleted');
      await loadTransactions();
    } catch (err) { setError(err.message); }
  };

  const handleCancel = () => { setEditId(null); setForm({ personId: '', amount: '', type: 'GIVE', note: '' }); };
  const getPersonName = (id) => persons.find(p => p.id === id)?.name || `#${id}`;
  const set = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  return (
    <>
      <div className="card">
        <h2>{editId ? 'Edit Transaction' : 'New Transaction'}</h2>
        {error && <div className="error">{error}</div>}
        {success && <div className="success">{success}</div>}
        {persons.length === 0 ? (
          <div className="empty">Add a contact first in the Persons page.</div>
        ) : (
          <form onSubmit={handleSubmit}>
            <div className="row">
              <div>
                <label>Person</label>
                <select required value={form.personId} onChange={set('personId')}>
                  <option value="">Select person</option>
                  {persons.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
              </div>
              <div>
                <label>Amount (₹)</label>
                <input type="number" step="0.01" min="0.01" required value={form.amount} onChange={set('amount')} />
              </div>
            </div>
            <div className="row">
              <div>
                <label>Type</label>
                <select value={form.type} onChange={set('type')}>
                  <option value="GIVE">GIVE</option>
                  <option value="RECEIVE">RECEIVE</option>
                </select>
              </div>
              <div>
                <label>Note (optional)</label>
                <input value={form.note} onChange={set('note')} />
              </div>
            </div>
            <div className="btn-row">
              <button className="btn-primary">{editId ? 'Update' : 'Create'}</button>
              {editId && <button type="button" className="btn-danger" onClick={handleCancel}>Cancel</button>}
            </div>
          </form>
        )}
      </div>

      <div className="card">
        <h2>Transactions</h2>
        <div className="tabs">
          {['ALL', 'GIVE', 'RECEIVE'].map(f => (
            <button key={f} className={`tab ${filter === f ? 'active' : ''}`} onClick={() => setFilter(f)}>{f}</button>
          ))}
        </div>
        {loading ? (
          <div className="empty">Loading...</div>
        ) : transactions.length === 0 ? (
          <div className="empty">No transactions found.</div>
        ) : (
          <table>
            <thead><tr><th>Date</th><th>Person</th><th>Type</th><th>Amount</th><th>Note</th><th>Actions</th></tr></thead>
            <tbody>
              {transactions.map(t => (
                <tr key={t.id}>
                  <td>{new Date(t.createdAt).toLocaleDateString()}</td>
                  <td>{getPersonName(t.personId)}</td>
                  <td><span className={`badge badge-${t.type.toLowerCase()}`}>{t.type}</span></td>
                  <td>₹{t.amount.toFixed(2)}</td>
                  <td>{t.note || '-'}</td>
                  <td>
                    <div className="btn-row">
                      <button className="btn-primary btn-sm" onClick={() => handleEdit(t)}>Edit</button>
                      <button className="btn-danger btn-sm" onClick={() => handleDelete(t.id)}>Delete</button>
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
