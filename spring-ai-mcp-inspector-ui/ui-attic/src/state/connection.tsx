import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { connectExternal, disconnectSession } from '../api/client';
import type {
  ConnectionConfig,
  ConnectionState,
  ConnectionStatus,
  TransportType,
} from '../lib/types';

interface ConnectionCtx extends ConnectionState {
  setConfig: (patch: Partial<ConnectionConfig>) => void;
  setTransport: (t: TransportType) => void;
  connect: () => Promise<void>;
  disconnect: () => Promise<void>;
}

const Ctx = createContext<ConnectionCtx | null>(null);

const DEFAULT_CONFIG: ConnectionConfig = {
  transport: 'stdio',
  command: '',
  args: '',
  url: '',
  env: {},
  headers: {},
};

export function ConnectionProvider({
  children,
  initialConfig,
}: {
  children: ReactNode;
  initialConfig?: Partial<ConnectionConfig>;
}) {
  const [config, setConfigState] = useState<ConnectionConfig>(() => ({
    ...DEFAULT_CONFIG,
    ...initialConfig,
  }));
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [status, setStatus] = useState<ConnectionStatus>('idle');
  const [error, setError] = useState<string | undefined>(undefined);

  const setConfig = useCallback((patch: Partial<ConnectionConfig>) => {
    setConfigState((cur) => ({ ...cur, ...patch }));
  }, []);

  const setTransport = useCallback((t: TransportType) => {
    setConfigState((cur) => ({ ...cur, transport: t }));
  }, []);

  const connect = useCallback(async () => {
    setStatus('connecting');
    setError(undefined);
    try {
      const result = await connectExternal(config);
      setSessionId(result.sessionId);
      setStatus('connected');
    } catch (err) {
      setStatus('error');
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [config]);

  const disconnect = useCallback(async () => {
    if (!sessionId) {
      setStatus('idle');
      return;
    }
    try {
      await disconnectSession(sessionId);
    } catch {
      // Even if disconnect fails, drop local state.
    } finally {
      setSessionId(null);
      setStatus('idle');
    }
  }, [sessionId]);

  const value = useMemo<ConnectionCtx>(
    () => ({
      config,
      sessionId,
      status,
      error,
      setConfig,
      setTransport,
      connect,
      disconnect,
    }),
    [config, sessionId, status, error, setConfig, setTransport, connect, disconnect],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useConnection(): ConnectionCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useConnection must be used inside <ConnectionProvider>');
  return ctx;
}
