"use client";

import React, { useEffect, useMemo, useState } from "react";
import {
  onAuthStateChanged,
  signInWithPopup,
  signOut,
  deleteUser,
  type User,
} from "firebase/auth";
import {
  collection,
  deleteDoc,
  doc,
  onSnapshot,
  query,
  serverTimestamp,
  updateDoc,
  where,
  writeBatch,
  getDocs,
} from "firebase/firestore";
import {
  Activity,
  Phone,
  MessageSquare,
  Mic,
  Smartphone,
  Shield,
  Settings,
  LogOut,
  Search,
  Trash2,
  RefreshCw,
} from "lucide-react";
import {
  getFirebaseAuth,
  getNamedFirestore,
  googleProvider,
  isFirebaseWebConfigured,
} from "@/lib/firebase";

type NavSection =
  | "dashboard"
  | "calls"
  | "sms"
  | "recordings"
  | "devices"
  | "privacy"
  | "settings";

interface CallDoc {
  id: string;
  phoneNumber: string;
  contactName: string;
  callType: "incoming" | "outgoing" | "missed" | "rejected";
  callTimestampMillis: number;
  durationSeconds: number;
  deviceId: string;
}

interface SmsDoc {
  id: string;
  address: string;
  contactName: string;
  messageBody: string;
  direction: "incoming" | "outgoing";
  deliveryStatus: string;
  messageTimestampMillis: number;
}

interface RecordingDoc {
  id: string;
  fileName: string;
  phoneNumber: string;
  contactName: string;
  durationSeconds: number;
  encryptionAlgorithm: string;
  authorizedForSync: boolean;
  recordedAtMillis: number;
}

interface DeviceDoc {
  id: string;
  deviceId: string;
  deviceName: string;
  manufacturer: string;
  model: string;
  osVersion: string;
  isConnected: boolean;
  callRecordingSupported: boolean;
}

interface SyncStatusDoc {
  id: string;
  deviceId: string;
  status: string;
  callsSyncedCount: number;
  smsSyncedCount: number;
  recordingsSyncedCount: number;
  lastSyncAt?: { seconds: number };
}

export default function DashboardPage() {
  const configured = isFirebaseWebConfigured();
  const [user, setUser] = useState<User | null>(null);
  const [authReady, setAuthReady] = useState(false);
  const [section, setSection] = useState<NavSection>("dashboard");
  const [calls, setCalls] = useState<CallDoc[]>([]);
  const [smsList, setSmsList] = useState<SmsDoc[]>([]);
  const [recordings, setRecordings] = useState<RecordingDoc[]>([]);
  const [devices, setDevices] = useState<DeviceDoc[]>([]);
  const [syncStatuses, setSyncStatuses] = useState<SyncStatusDoc[]>([]);
  const [callQuery, setCallQuery] = useState("");
  const [callFilter, setCallFilter] = useState("all");
  const [callPage, setCallPage] = useState(1);
  const [smsQuery, setSmsQuery] = useState("");
  const [statusBanner, setStatusBanner] = useState<string | null>(null);

  useEffect(() => {
    if (!configured) {
      setAuthReady(true);
      return;
    }
    const auth = getFirebaseAuth();
    return onAuthStateChanged(auth, (u) => {
      setUser(u);
      setAuthReady(true);
    });
  }, [configured]);

  useEffect(() => {
    if (!configured || !user) return;
    const db = getNamedFirestore();
    const uid = user.uid;

    const unsubCalls = onSnapshot(
      query(collection(db, "call_records"), where("ownerUserId", "==", uid)),
      (snap) => {
        const rows = snap.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<CallDoc, "id">) }));
        rows.sort((a, b) => b.callTimestampMillis - a.callTimestampMillis);
        setCalls(rows);
      }
    );
    const unsubSms = onSnapshot(
      query(collection(db, "sms_records"), where("ownerUserId", "==", uid)),
      (snap) => {
        const rows = snap.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<SmsDoc, "id">) }));
        rows.sort((a, b) => b.messageTimestampMillis - a.messageTimestampMillis);
        setSmsList(rows);
      }
    );
    const unsubRecs = onSnapshot(
      query(collection(db, "recordings"), where("ownerUserId", "==", uid)),
      (snap) => {
        const rows = snap.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<RecordingDoc, "id">) }));
        rows.sort((a, b) => b.recordedAtMillis - a.recordedAtMillis);
        setRecordings(rows);
      }
    );
    const unsubDevices = onSnapshot(
      query(collection(db, "devices"), where("ownerUserId", "==", uid)),
      (snap) => {
        setDevices(snap.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<DeviceDoc, "id">) })));
      }
    );
    const unsubSync = onSnapshot(
      query(collection(db, "sync_status"), where("ownerUserId", "==", uid)),
      (snap) => {
        setSyncStatuses(snap.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<SyncStatusDoc, "id">) })));
      }
    );

    return () => {
      unsubCalls();
      unsubSms();
      unsubRecs();
      unsubDevices();
      unsubSync();
    };
  }, [configured, user]);

  const filteredCalls = useMemo(() => {
    const q = callQuery.trim().toLowerCase();
    return calls.filter((c) => {
      const matchType = callFilter === "all" || c.callType === callFilter;
      const matchQ =
        !q || c.phoneNumber.toLowerCase().includes(q) || c.contactName.toLowerCase().includes(q);
      return matchType && matchQ;
    });
  }, [calls, callQuery, callFilter]);

  const filteredSms = useMemo(() => {
    const q = smsQuery.trim().toLowerCase();
    return smsList.filter(
      (s) =>
        !q ||
        s.address.toLowerCase().includes(q) ||
        s.contactName.toLowerCase().includes(q) ||
        s.messageBody.toLowerCase().includes(q)
    );
  }, [smsList, smsQuery]);

  if (!configured) {
    return (
      <main className="min-h-screen flex items-center justify-center p-6">
        <div className="max-w-lg w-full bg-slate-900 border border-slate-800 rounded-2xl p-8 space-y-4">
          <h1 className="text-2xl font-bold text-white">Connect Activity Web Dashboard</h1>
          <p className="text-slate-300 text-sm">
            Configure <code className="text-sky-400">web-dashboard/.env.local</code> from{" "}
            <code className="text-sky-400">.env.local.example</code> with your Firebase Web App credentials
            to connect to project <code className="text-emerald-400">gen-lang-client-0587220162</code>.
          </p>
        </div>
      </main>
    );
  }

  if (!authReady) {
    return <main className="min-h-screen flex items-center justify-center text-slate-400">Loading…</main>;
  }

  if (!user) {
    return (
      <main className="min-h-screen flex items-center justify-center p-6">
        <div className="max-w-md w-full bg-slate-900 border border-slate-800 rounded-2xl p-8 space-y-6">
          <div className="space-y-2">
            <h1 className="text-2xl font-bold text-white">Connect Activity Manager</h1>
            <p className="text-slate-400 text-sm">
              Sign in with the same verified Google account connected to your Android device.
            </p>
          </div>
          <button
            onClick={() => signInWithPopup(getFirebaseAuth(), googleProvider)}
            className="w-full py-3 px-4 rounded-xl bg-blue-600 hover:bg-blue-500 font-semibold text-white transition"
          >
            Sign in with Google
          </button>
        </div>
      </main>
    );
  }

  const pageSize = 20;
  const pagedCalls = filteredCalls.slice(0, callPage * pageSize);

  async function handleDeleteCloudData() {
    if (!user) return;
    const db = getNamedFirestore();
    const cols = ["call_records", "sms_records", "recordings", "consent_records", "sync_status", "authorized_shares", "devices", "audit_logs"];
    for (const c of cols) {
      const snap = await getDocs(query(collection(db, c), where("ownerUserId", "==", user.uid)));
      const batch = writeBatch(db);
      snap.docs.forEach((d) => batch.delete(d.ref));
      await batch.commit();
    }
    setStatusBanner("All cloud-synchronized data deleted.");
  }

  async function handleDeleteAccount() {
    if (!user) return;
    await handleDeleteCloudData();
    const db = getNamedFirestore();
    await deleteDoc(doc(db, "users", user.uid));
    await deleteUser(user);
  }

  const navItems: { id: NavSection; label: string; icon: React.ReactNode }[] = [
    { id: "dashboard", label: "Dashboard", icon: <Activity className="w-4 h-4" /> },
    { id: "calls", label: "Call History", icon: <Phone className="w-4 h-4" /> },
    { id: "sms", label: "SMS History", icon: <MessageSquare className="w-4 h-4" /> },
    { id: "recordings", label: "Recordings", icon: <Mic className="w-4 h-4" /> },
    { id: "devices", label: "Devices", icon: <Smartphone className="w-4 h-4" /> },
    { id: "privacy", label: "Privacy", icon: <Shield className="w-4 h-4" /> },
    { id: "settings", label: "Settings", icon: <Settings className="w-4 h-4" /> },
  ];

  return (
    <div className="min-h-screen flex flex-col md:flex-row">
      <aside className="w-full md:w-64 bg-slate-900 border-b md:border-b-0 md:border-r border-slate-800 p-4 flex flex-col gap-2">
        <div className="px-3 py-2 mb-2">
          <h2 className="font-bold text-lg text-white">Connect Activity</h2>
          <p className="text-xs text-slate-400 truncate">{user.email}</p>
        </div>
        <nav className="flex md:flex-col gap-1 overflow-x-auto">
          {navItems.map((item) => (
            <button
              key={item.id}
              onClick={() => setSection(item.id)}
              className={`flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium whitespace-nowrap transition ${
                section === item.id ? "bg-blue-600 text-white" : "text-slate-300 hover:bg-slate-800"
              }`}
            >
              {item.icon}
              <span>{item.label}</span>
            </button>
          ))}
        </nav>
        <div className="mt-auto pt-4 hidden md:block">
          <button
            onClick={() => signOut(getFirebaseAuth())}
            className="w-full flex items-center gap-2 px-3 py-2 rounded-xl text-sm text-slate-300 hover:bg-slate-800"
          >
            <LogOut className="w-4 h-4" />
            Sign Out
          </button>
        </div>
      </aside>

      <main className="flex-1 p-6 md:p-8 space-y-6 max-w-6xl">
        {statusBanner && (
          <div className="p-3 rounded-xl bg-emerald-950 border border-emerald-800 text-emerald-200 text-sm">
            {statusBanner}
          </div>
        )}

        {section === "dashboard" && (
          <section className="space-y-6">
            <h1 className="text-2xl font-bold">Overview</h1>
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
              <StatTile label="Incoming Calls" value={calls.filter((c) => c.callType === "incoming").length} />
              <StatTile label="Outgoing Calls" value={calls.filter((c) => c.callType === "outgoing").length} />
              <StatTile label="Missed Calls" value={calls.filter((c) => c.callType === "missed").length} />
              <StatTile label="SMS Records" value={smsList.length} />
              <StatTile label="Recordings" value={recordings.length} />
              <StatTile label="Connected Devices" value={devices.filter((d) => d.isConnected).length} />
            </div>
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 space-y-3">
              <h2 className="font-semibold flex items-center gap-2">
                <RefreshCw className="w-4 h-4 text-sky-400" /> Latest Sync Status
              </h2>
              {syncStatuses.length === 0 ? (
                <p className="text-sm text-slate-400">No synchronization events yet.</p>
              ) : (
                syncStatuses.map((s) => (
                  <div key={s.id} className="text-sm text-slate-300 flex justify-between border-t border-slate-800 pt-2">
                    <span>Device {s.deviceId} • {s.status.toUpperCase()}</span>
                    <span>{s.callsSyncedCount} calls / {s.smsSyncedCount} SMS / {s.recordingsSyncedCount} rec</span>
                  </div>
                ))
              )}
            </div>
          </section>
        )}

        {section === "calls" && (
          <section className="space-y-4">
            <h1 className="text-2xl font-bold">Call History</h1>
            <div className="flex flex-wrap gap-3">
              <div className="relative flex-1 min-w-[220px]">
                <Search className="w-4 h-4 text-slate-400 absolute left-3 top-3" />
                <input
                  value={callQuery}
                  onChange={(e) => { setCallQuery(e.target.value); setCallPage(1); }}
                  placeholder="Search number or contact..."
                  className="w-full pl-9 pr-3 py-2 rounded-xl bg-slate-900 border border-slate-800 text-sm"
                />
              </div>
              <select
                value={callFilter}
                onChange={(e) => { setCallFilter(e.target.value); setCallPage(1); }}
                className="px-3 py-2 rounded-xl bg-slate-900 border border-slate-800 text-sm"
              >
                <option value="all">All Types</option>
                <option value="incoming">Incoming</option>
                <option value="outgoing">Outgoing</option>
                <option value="missed">Missed</option>
                <option value="rejected">Rejected</option>
              </select>
            </div>
            <div className="overflow-x-auto bg-slate-900 border border-slate-800 rounded-2xl">
              <table className="w-full text-sm text-left">
                <thead className="text-slate-400 border-b border-slate-800">
                  <tr>
                    <th className="p-3">Contact / Number</th>
                    <th className="p-3">Type</th>
                    <th className="p-3">Date &amp; Time</th>
                    <th className="p-3">Duration</th>
                  </tr>
                </thead>
                <tbody>
                  {pagedCalls.map((c) => (
                    <tr key={c.id} className="border-b border-slate-800/60">
                      <td className="p-3">{c.contactName || c.phoneNumber}</td>
                      <td className="p-3 uppercase text-xs font-mono">{c.callType}</td>
                      <td className="p-3">{new Date(c.callTimestampMillis).toLocaleString()}</td>
                      <td className="p-3 font-mono">{c.durationSeconds}s</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {pagedCalls.length < filteredCalls.length && (
              <button
                onClick={() => setCallPage((p) => p + 1)}
                className="px-4 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-sm"
              >
                Load More ({pagedCalls.length} of {filteredCalls.length})
              </button>
            )}
          </section>
        )}

        {section === "sms" && (
          <section className="space-y-4">
            <h1 className="text-2xl font-bold">SMS History</h1>
            <input
              value={smsQuery}
              onChange={(e) => setSmsQuery(e.target.value)}
              placeholder="Search sender, recipient, or message..."
              className="w-full px-4 py-2 rounded-xl bg-slate-900 border border-slate-800 text-sm"
            />
            <div className="space-y-3">
              {filteredSms.map((m) => (
                <div key={m.id} className="p-4 rounded-2xl bg-slate-900 border border-slate-800 space-y-1">
                  <div className="flex justify-between text-xs text-slate-400">
                    <span className="font-semibold text-slate-200">{m.contactName || m.address}</span>
                    <span className="uppercase font-mono">{m.direction} • {m.deliveryStatus}</span>
                  </div>
                  <p className="text-sm text-slate-100">{m.messageBody}</p>
                  <p className="text-xs text-slate-500">{new Date(m.messageTimestampMillis).toLocaleString()}</p>
                </div>
              ))}
            </div>
          </section>
        )}

        {section === "recordings" && (
          <section className="space-y-4">
            <h1 className="text-2xl font-bold">Call Recordings Metadata</h1>
            <p className="text-sm text-slate-400">
              Audio payloads remain encrypted at rest on the originating Android device with Android Keystore AES-256-GCM unless explicitly uploaded through an authenticated storage bucket. No public URLs are exposed.
            </p>
            <div className="space-y-3">
              {recordings.map((r) => (
                <div key={r.id} className="p-4 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-between">
                  <div>
                    <p className="font-semibold">{r.fileName}</p>
                    <p className="text-xs text-slate-400">
                      {r.contactName || r.phoneNumber} • {r.durationSeconds}s • {r.encryptionAlgorithm}
                    </p>
                  </div>
                  <button
                    onClick={() => deleteDoc(doc(getNamedFirestore(), "recordings", r.id))}
                    className="p-2 text-rose-400 hover:bg-slate-800 rounded-lg"
                    title="Delete recording metadata"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
              ))}
            </div>
          </section>
        )}

        {section === "devices" && (
          <section className="space-y-4">
            <h1 className="text-2xl font-bold">Connected Devices</h1>
            <div className="space-y-3">
              {devices.map((d) => (
                <div key={d.id} className="p-4 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-between">
                  <div>
                    <p className="font-semibold">{d.deviceName}</p>
                    <p className="text-xs text-slate-400">
                      Android {d.osVersion} • {d.isConnected ? "Connected" : "Disconnected"} • Native Recording: {d.callRecordingSupported ? "Supported" : "Unsupported"}
                    </p>
                  </div>
                  {d.isConnected && (
                    <button
                      onClick={() => updateDoc(doc(getNamedFirestore(), "devices", d.id), { isConnected: false, updatedAt: serverTimestamp() })}
                      className="px-3 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-xs"
                    >
                      Disconnect
                    </button>
                  )}
                </div>
              ))}
            </div>
          </section>
        )}

        {(section === "privacy" || section === "settings") && (
          <section className="space-y-4">
            <h1 className="text-2xl font-bold">{section === "privacy" ? "Privacy & Data Deletion" : "Account Settings"}</h1>
            <div className="p-5 rounded-2xl bg-slate-900 border border-slate-800 space-y-4">
              <p className="text-sm text-slate-300">Account: {user.email} (UID: {user.uid})</p>
              <div className="flex flex-wrap gap-3">
                <button
                  onClick={handleDeleteCloudData}
                  className="px-4 py-2 rounded-xl bg-amber-700 hover:bg-amber-600 text-sm font-medium"
                >
                  Delete All Cloud Data
                </button>
                <button
                  onClick={handleDeleteAccount}
                  className="px-4 py-2 rounded-xl bg-rose-700 hover:bg-rose-600 text-sm font-medium"
                >
                  Delete Account Permanently
                </button>
              </div>
            </div>
          </section>
        )}
      </main>
    </div>
  );
}

function StatTile({ label, value }: { label: string; value: number }) {
  return (
    <div className="p-4 rounded-2xl bg-slate-900 border border-slate-800">
      <p className="text-xs text-slate-400">{label}</p>
      <p className="text-2xl font-bold mt-1">{value}</p>
    </div>
  );
}
