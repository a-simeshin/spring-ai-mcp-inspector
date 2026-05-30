import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import type { ConfigDto } from '../api/client';
import { getConfig } from '../api/client';

/** Inspector runtime state exposed through the {@link InspectorContext}. */
export interface InspectorState {
  config: ConfigDto | null;
  configError: string | null;
  configLoading: boolean;
  refreshConfig: () => void;
}

const InspectorContext = createContext<InspectorState | null>(null);

/** Hook for accessing the inspector global state. */
export function useInspector(): InspectorState {
  const ctx = useContext(InspectorContext);
  if (!ctx) {
    throw new Error('useInspector must be used inside <InspectorProvider>');
  }
  return ctx;
}

/** Reads inspector config from the backend with manual refresh support. */
export function useInspectorState(): InspectorState {
  const [config, setConfig] = useState<ConfigDto | null>(null);
  const [configError, setConfigError] = useState<string | null>(null);
  const [configLoading, setConfigLoading] = useState(true);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let ignore = false;
    setConfigLoading(true);
    setConfigError(null);
    getConfig()
      .then((data) => {
        if (!ignore) {
          setConfig(data);
        }
      })
      .catch((err: unknown) => {
        if (!ignore) {
          setConfigError(err instanceof Error ? err.message : String(err));
        }
      })
      .finally(() => {
        if (!ignore) {
          setConfigLoading(false);
        }
      });
    return () => {
      ignore = true;
    };
  }, [tick]);

  return useMemo<InspectorState>(
    () => ({
      config,
      configError,
      configLoading,
      refreshConfig: () => setTick((value) => value + 1),
    }),
    [config, configError, configLoading],
  );
}

export { InspectorContext };
