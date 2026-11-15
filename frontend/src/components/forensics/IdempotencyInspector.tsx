import React, { useEffect, useState, useTransition } from 'react';
import {
  Search,
  ShieldCheck,
  AlertTriangle,
  RefreshCw,
  Copy,
  Check,
  Lock,
} from 'lucide-react';
import { IdempotencyForensicDto } from '../../types/dashboard';
import { searchIdempotency } from '../../services/dashboardApi';

export const IdempotencyInspector: React.FC = () => {
  const [records, setRecords] = useState<IdempotencyForensicDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Filters
  const [searchKey, setSearchKey] = useState('');
  const [searchHash, setSearchHash] = useState('');
  const [searchTenant, setSearchTenant] = useState('');
  const [statusFilter, setStatusFilter] = useState('');

  // Tampering detection tool
  const [verifyPayload, setVerifyPayload] = useState('');
  const [computedHash, setComputedHash] = useState<string | null>(null);

  // Detail modal
  const [selectedRecord, setSelectedRecord] = useState<IdempotencyForensicDto | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const [, startTransition] = useTransition();

  const loadRecords = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await searchIdempotency({
        key: searchKey || undefined,
        payloadHash: searchHash || undefined,
        tenantId: searchTenant || undefined,
        status: statusFilter || undefined,
      });
      setRecords(data);
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('Failed to load idempotency records.');
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadRecords();
  }, [statusFilter]);

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    loadRecords();
  };

  // Compute SHA-256 in browser for tampering detection
  const handleVerifyPayloadChange = async (text: string) => {
    setVerifyPayload(text);
    if (!text.trim()) {
      setComputedHash(null);
      return;
    }
    try {
      const encoder = new TextEncoder();
      const data = encoder.encode(text.trim());
      const hashBuffer = await crypto.subtle.digest('SHA-256', data);
      const hashArray = Array.from(new Uint8Array(hashBuffer));
      const hashHex = hashArray.map((b) => b.toString(16).padStart(2, '0')).join('');
      startTransition(() => {
        setComputedHash(hashHex);
      });
    } catch {
      setComputedHash(null);
    }
  };

  const handleCopy = (id: string, text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedKey(id);
    setTimeout(() => setCopiedKey(null), 2000);
  };

  return (
    <div className="flex flex-col h-full bg-slate-950 p-6 overflow-y-auto">
      {/* Page Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-6">
        <div>
          <h1 className="text-xl font-bold text-slate-100 flex items-center gap-2">
            <Lock className="w-5 h-5 text-primary-400" />
            Idempotency Inspector & Forensics
          </h1>
          <p className="text-xs text-slate-400 mt-1">
            Audit idempotency locks, verify cryptographic request hashes, and detect payload tampering
          </p>
        </div>

        <button
          onClick={loadRecords}
          disabled={loading}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-900 border border-slate-800 text-xs text-slate-300 hover:text-white hover:bg-slate-800 transition-colors w-fit"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>

      {/* Filter and Tampering Detection Bar */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 mb-6">
        {/* Search Query Card */}
        <form
          onSubmit={handleSearchSubmit}
          className="lg:col-span-2 bg-slate-900/80 border border-slate-800 rounded-xl p-4 space-y-3"
        >
          <div className="text-xs font-semibold text-slate-300 flex items-center gap-1.5">
            <Search className="w-4 h-4 text-sky-400" />
            <span>Forensic Search Filters</span>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-4 gap-3">
            <div>
              <label htmlFor="search-key-input" className="block text-[11px] text-slate-400 mb-1">Idempotency Key</label>
              <input
                id="search-key-input"
                type="text"
                value={searchKey}
                onChange={(e) => setSearchKey(e.target.value)}
                placeholder="Substring search..."
                className="w-full px-2.5 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-200 placeholder:text-slate-600 focus:outline-none focus:border-sky-500"
              />
            </div>

            <div>
              <label htmlFor="search-tenant-input" className="block text-[11px] text-slate-400 mb-1">Tenant ID</label>
              <input
                id="search-tenant-input"
                type="text"
                value={searchTenant}
                onChange={(e) => setSearchTenant(e.target.value)}
                placeholder="Exact tenant..."
                className="w-full px-2.5 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-200 placeholder:text-slate-600 focus:outline-none focus:border-sky-500"
              />
            </div>

            <div>
              <label htmlFor="search-hash-input" className="block text-[11px] text-slate-400 mb-1">SHA-256 Hash</label>
              <input
                id="search-hash-input"
                type="text"
                value={searchHash}
                onChange={(e) => setSearchHash(e.target.value)}
                placeholder="Exact 64-char hex..."
                className="w-full px-2.5 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-200 placeholder:text-slate-600 focus:outline-none focus:border-sky-500"
              />
            </div>

            <div>
              <label htmlFor="status-filter-select" className="block text-[11px] text-slate-400 mb-1">Status</label>
              <select
                id="status-filter-select"
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
                className="w-full px-2.5 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-200 focus:outline-none focus:border-sky-500"
              >
                <option value="">All Statuses</option>
                <option value="RESOLVED">RESOLVED (Success)</option>
                <option value="PROCESSING">PROCESSING (Locked)</option>
                <option value="PENDING">PENDING</option>
                <option value="FAILED">FAILED</option>
              </select>
            </div>
          </div>

          <div className="flex justify-end gap-2 pt-1">
            <button
              type="submit"
              className="px-3 py-1.5 bg-sky-600 hover:bg-sky-500 text-white rounded-lg text-xs font-semibold transition-colors flex items-center gap-1.5"
            >
              <Search className="w-3.5 h-3.5" />
              Search Records
            </button>
          </div>
        </form>

        {/* Cryptographic Tampering Validator */}
        <div className="bg-slate-900/80 border border-slate-800 rounded-xl p-4 flex flex-col justify-between">
          <div>
            <div className="text-xs font-semibold text-slate-300 flex items-center gap-1.5 mb-2">
              <ShieldCheck className="w-4 h-4 text-emerald-400" />
              <span>SHA-256 Tampering Verifier</span>
            </div>
            <p className="text-[11px] text-slate-400 mb-2">
              Paste request payload to verify against stored record digests:
            </p>
            <textarea
              rows={2}
              value={verifyPayload}
              onChange={(e) => handleVerifyPayloadChange(e.target.value)}
              placeholder="Paste JSON payload here..."
              className="w-full px-2.5 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-xs font-mono text-emerald-300 placeholder:text-slate-600 focus:outline-none focus:border-emerald-500 resize-none"
            />
          </div>

          {computedHash && (
            <div className="mt-2 p-2 rounded bg-slate-950 border border-slate-800">
              <span className="text-[10px] text-slate-400 block mb-0.5">Calculated Digest:</span>
              <div className="font-mono text-[11px] text-emerald-400 break-all select-all">
                {computedHash}
              </div>
            </div>
          )}
        </div>
      </div>

      {error && (
        <div className="mb-4 p-3 rounded-xl bg-rose-950/40 border border-rose-500/50 text-rose-300 text-xs">
          {error}
        </div>
      )}

      {/* Table Results */}
      <div className="flex-1 bg-slate-900/60 border border-slate-800 rounded-xl overflow-hidden flex flex-col">
        <div className="overflow-x-auto flex-1">
          <table className="w-full text-left text-xs border-collapse">
            <thead>
              <tr className="border-b border-slate-800 bg-slate-900/90 text-slate-400 font-semibold">
                <th className="py-3 px-4">Idempotency Key</th>
                <th className="py-3 px-4">Tenant</th>
                <th className="py-3 px-4">Status</th>
                <th className="py-3 px-4">Request Hash</th>
                <th className="py-3 px-4">Lease Expiry</th>
                <th className="py-3 px-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/60 font-mono">
              {records.length === 0 ? (
                <tr>
                  <td colSpan={6} className="text-center py-12 text-slate-500 font-sans">
                    {loading ? 'Searching records...' : 'No idempotency records matched the query'}
                  </td>
                </tr>
              ) : (
                records.map((rec) => {
                  const isTampered =
                    computedHash &&
                    computedHash.toLowerCase() !== rec.payloadHash.toLowerCase();
                  const isMatched =
                    computedHash &&
                    computedHash.toLowerCase() === rec.payloadHash.toLowerCase();

                  return (
                    <tr
                      key={rec.idempotencyKey}
                      className={`hover:bg-slate-800/40 transition-colors ${
                        isMatched
                          ? 'bg-emerald-950/20 border-l-2 border-emerald-500'
                          : isTampered && verifyPayload
                          ? 'bg-rose-950/20 border-l-2 border-rose-500'
                          : ''
                      }`}
                    >
                      <td className="py-3 px-4">
                        <div className="flex items-center gap-1.5">
                          <span className="font-bold text-slate-200 truncate max-w-[200px]" title={rec.idempotencyKey}>
                            {rec.idempotencyKey}
                          </span>
                          <button
                            onClick={() => handleCopy(`key-${rec.idempotencyKey}`, rec.idempotencyKey)}
                            className="text-slate-500 hover:text-slate-300"
                            aria-label="Copy idempotency key"
                          >
                            {copiedKey === `key-${rec.idempotencyKey}` ? (
                              <Check className="w-3 h-3 text-emerald-400" />
                            ) : (
                              <Copy className="w-3 h-3" />
                            )}
                          </button>
                        </div>
                      </td>

                      <td className="py-3 px-4 text-slate-300">{rec.tenantId}</td>

                      <td className="py-3 px-4">
                        <span
                          className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-sans font-bold ${
                            rec.status === 'RESOLVED'
                              ? 'bg-emerald-500/20 text-emerald-300'
                              : rec.status === 'PROCESSING'
                              ? 'bg-sky-500/20 text-sky-300 animate-pulse'
                              : rec.status === 'FAILED'
                              ? 'bg-rose-500/20 text-rose-300'
                              : 'bg-amber-500/20 text-amber-300'
                          }`}
                        >
                          {rec.status}
                        </span>
                      </td>

                      <td className="py-3 px-4">
                        <div className="flex items-center gap-1.5">
                          <span
                            className={`text-[11px] truncate max-w-[140px] ${
                              isMatched
                                ? 'text-emerald-400 font-bold'
                                : isTampered && verifyPayload
                                ? 'text-rose-400 font-bold'
                                : 'text-slate-400'
                            }`}
                            title={rec.payloadHash}
                          >
                            {rec.payloadHash.substring(0, 16)}...
                          </span>
                          {isMatched && (
                            <span className="flex items-center gap-0.5 text-[10px] px-1.5 py-0.2 rounded bg-emerald-500/20 text-emerald-300 font-sans">
                              <ShieldCheck className="w-3 h-3" /> Match
                            </span>
                          )}
                          {isTampered && verifyPayload && (
                            <span className="flex items-center gap-0.5 text-[10px] px-1.5 py-0.2 rounded bg-rose-500/20 text-rose-300 font-sans">
                              <AlertTriangle className="w-3 h-3" /> Mismatch
                            </span>
                          )}
                        </div>
                      </td>

                      <td className="py-3 px-4 text-slate-400 text-[11px]">
                        {rec.leaseExpiresAt ? new Date(rec.leaseExpiresAt).toLocaleTimeString() : 'N/A'}
                      </td>

                      <td className="py-3 px-4 text-right">
                        <button
                          onClick={() => setSelectedRecord(rec)}
                          className="px-2.5 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-200 text-[11px] font-sans transition-colors"
                        >
                          Inspect
                        </button>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Record Inspect Modal */}
      {selectedRecord && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-in fade-in"
          role="dialog"
          aria-modal="true"
          aria-labelledby="record-inspector-modal-title"
        >
          <div className="w-full max-w-xl bg-slate-900 border border-slate-800 rounded-2xl shadow-2xl p-6 space-y-4">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <div>
                <h3 id="record-inspector-modal-title" className="text-sm font-bold text-slate-100">
                  Idempotency Record Forensic Details
                </h3>
                <span className="text-xs font-mono text-slate-400">{selectedRecord.idempotencyKey}</span>
              </div>
              <button
                onClick={() => setSelectedRecord(null)}
                className="text-slate-400 hover:text-white"
                aria-label="Close details"
              >
                ✕
              </button>
            </div>

            <div className="space-y-3 text-xs">
              <div className="grid grid-cols-2 gap-3">
                <div className="bg-slate-950 p-2.5 rounded border border-slate-800">
                  <span className="text-slate-500 block mb-0.5 font-sans">Status</span>
                  <span className="font-bold text-slate-200">{selectedRecord.status}</span>
                </div>
                <div className="bg-slate-950 p-2.5 rounded border border-slate-800">
                  <span className="text-slate-500 block mb-0.5 font-sans">Cached HTTP Status</span>
                  <span className="font-bold text-slate-200">{selectedRecord.statusCode ?? 'None'}</span>
                </div>
              </div>

              <div>
                <span className="text-slate-400 block mb-1 font-sans">Full Request SHA-256 Hash</span>
                <pre className="font-mono text-[11px] bg-slate-950 p-2 rounded border border-slate-800 text-sky-400 break-all select-all">
                  {selectedRecord.payloadHash}
                </pre>
              </div>

              <div>
                <span className="text-slate-400 block mb-1 font-sans">Cached Response Body</span>
                <pre className="font-mono text-[11px] bg-slate-950 p-3 rounded border border-slate-800 text-emerald-400 max-h-48 overflow-y-auto">
                  {selectedRecord.cachedResponse || 'No cached response payload'}
                </pre>
              </div>
            </div>

            <div className="flex justify-end pt-2 border-t border-slate-800">
              <button
                onClick={() => setSelectedRecord(null)}
                className="px-4 py-1.5 rounded-lg bg-slate-800 text-slate-300 hover:bg-slate-700 text-xs font-medium"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
