import React, { useState, useEffect, useCallback } from 'react';
import { getMyTransactions, getMyPersons, createBackup } from '../api';

export default function Dashboard({ user }) {
  const [transactions, setTransactions] = useState([]);
  const [personCount, setPersonCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [backupStatus, setBackupStatus] = useState(null);
  const [backingUp, setBackingUp] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [txns, persons] = await Promise.all([getMyTransactions(), getMyPersons()]);
      setTransactions(txns);
      setPersonCount(persons.length);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const totalGive = transactions.filter(t => t.type === 'GIVE').reduce((s, t) => s + t.amount, 0);
  const totalReceive = transactions.filter(t => t.type === 'RECEIVE').reduce((s, t) => s + t.amount, 0);

  const handleBackup = async () => {
    setBackingUp(true);
    setBackupStatus(null);
    try {
      const result = await createBackup();
      setBackupStatus(result);
    } catch (err) {
      setBackupStatus({ status: 'FAILED', message: err.message });
    } finally {
      setBackingUp(false);
    }
  };

  if (loading) return <div className="card empty">Loading...</div>;

  return (
    <>
      <div className="card">
        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
          {user.pictureUrl && (
            <img src={user.pictureUrl} alt="" style={{ width: 48, height: 48, borderRadius: '50%' }} referrerPolicy="no-referrer" />
          )}
          <div>
            <h2 style={{ marginBottom: '0.2rem' }}>Welcome, {user.name}</h2>
            <p style={{ color: '#777', fontSize: '0.9rem' }}>
              {user.email} &middot; {personCount} contact{personCount !== 1 ? 's' : ''} &middot; {transactions.length} transaction{transactions.length !== 1 ? 's' : ''}
            </p>
          </div>
        </div>
      </div>

      <div className="summary">
        <div className="summary-card sc-give">
          <h3>Total Given</h3>
          <p>₹{totalGive.toFixed(2)}</p>
        </div>
        <div className="summary-card sc-receive">
          <h3>Total Received</h3>
          <p>₹{totalReceive.toFixed(2)}</p>
        </div>
      </div>

      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
          <h2 style={{ marginBottom: 0 }}>Recent Transactions</h2>
          <button className="btn-primary" onClick={handleBackup} disabled={backingUp}>
            {backingUp ? 'Backing up...' : '☁ Backup to Drive'}
          </button>
        </div>
        {backupStatus && (
          <div className={backupStatus.status === 'SUCCESS' ? 'success' : 'error'}>
            {backupStatus.message}
            {backupStatus.driveLink && (
              <> — <a href={backupStatus.driveLink} target="_blank" rel="noreferrer">View on Drive</a></>
            )}
          </div>
        )}
        {transactions.length === 0 ? (
          <div className="empty">No transactions yet. Go to Transactions to add one.</div>
        ) : (
          <table>
            <thead>
              <tr><th>Date</th><th>Type</th><th>Amount</th><th>Note</th></tr>
            </thead>
            <tbody>
              {transactions.slice(0, 10).map(t => (
                <tr key={t.id}>
                  <td>{new Date(t.createdAt).toLocaleDateString()}</td>
                  <td><span className={`badge badge-${t.type.toLowerCase()}`}>{t.type}</span></td>
                  <td>₹{t.amount.toFixed(2)}</td>
                  <td>{t.note || '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
