const API_BASE = '/api/v1';

async function request(url, options = {}) {
  const config = { headers: { 'Content-Type': 'application/json' }, ...options };
  const res = await fetch(`${API_BASE}${url}`, config);
  if (res.status === 401) {
    window.location.href = '/oauth2/authorization/google';
    return;
  }
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    const msg = body?.message
      || (body?.fieldErrors ? Object.entries(body.fieldErrors).map(([k, v]) => `${k}: ${v}`).join(', ') : null)
      || `Request failed (${res.status})`;
    throw new Error(msg);
  }
  if (res.status === 204) return null;
  return res.json();
}

// Auth
export const getMe = () => request('/auth/me');
export const logout = () => fetch('/api/v1/auth/logout', { method: 'POST' });

// User profile
export const getMyProfile = () => request('/users/me');
export const updateMyProfile = (data) => request('/users/me', { method: 'PUT', body: JSON.stringify(data) });

// Persons
export const createPerson = (data) => request('/persons', { method: 'POST', body: JSON.stringify(data) });
export const getMyPersons = () => request('/persons');
export const updatePerson = (id, data) => request(`/persons/${id}`, { method: 'PUT', body: JSON.stringify(data) });
export const deletePerson = (id) => request(`/persons/${id}`, { method: 'DELETE' });
export const getGoogleContacts = () => request('/persons/google-contacts');

// Transactions
export const createTransaction = (data) => request('/transactions', { method: 'POST', body: JSON.stringify(data) });
export const getMyTransactions = () => request('/transactions');
export const getMyTransactionsByType = (type) => request(`/transactions/type/${type}`);
export const updateTransaction = (id, data) => request(`/transactions/${id}`, { method: 'PUT', body: JSON.stringify(data) });
export const deleteTransaction = (id) => request(`/transactions/${id}`, { method: 'DELETE' });

// Backup
export const createBackup = () => request('/backup', { method: 'POST' });
