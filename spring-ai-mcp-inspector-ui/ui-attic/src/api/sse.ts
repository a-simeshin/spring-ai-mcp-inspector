/**
 * SSE subscription helper for inspector notifications.
 *
 * EventSource cannot set custom headers, so the auth token is sent via
 * `?auth=` query parameter. The backend must accept the token via either the
 * header or the query string for `/api/events`.
 */

declare global {
  interface Window {
    __INSPECTOR_AUTH_TOKEN__?: string;
  }
}

/** Single inspector notification frame delivered over SSE. */
export interface InspectorEvent {
  type: string;
  payload: unknown;
  timestamp: number;
}

/** Server-initiated sampling/createMessage request payload. */
export interface SamplingRequestEvent {
  requestId: string;
  params: unknown;
}

/** Server-initiated elicitation/create request payload. */
export interface ElicitationRequestEvent {
  requestId: string;
  params: unknown;
}

/** Typed event handlers for SSE traffic. */
export interface SubscribeHandlers {
  /** Catch-all for any event not consumed by a typed handler. */
  onNotification?: (event: InspectorEvent) => void;
  onSamplingRequest?: (event: SamplingRequestEvent) => void;
  onElicitationRequest?: (event: ElicitationRequestEvent) => void;
  onRootsListChanged?: (payload: unknown) => void;
  onLog?: (payload: unknown) => void;
  onError?: (error: Event) => void;
}

/** Subscription handle returned by {@link subscribeEvents}. */
export interface EventSubscription {
  close(): void;
}

const EVENTS_URL = '/mcp-inspector/api/events';

function dispatchTyped(handlers: SubscribeHandlers, event: InspectorEvent): boolean {
  switch (event.type) {
    case 'mcp:sampling-request':
      handlers.onSamplingRequest?.(event.payload as SamplingRequestEvent);
      return true;
    case 'mcp:elicitation-request':
      handlers.onElicitationRequest?.(event.payload as ElicitationRequestEvent);
      return true;
    case 'mcp:roots-list-changed':
      handlers.onRootsListChanged?.(event.payload);
      return true;
    case 'mcp:log':
      handlers.onLog?.(event.payload);
      return true;
    default:
      return false;
  }
}

/**
 * Opens an EventSource against the inspector events endpoint and dispatches
 * decoded messages either to a single-callback (legacy) or to a typed handler
 * map.
 *
 * @returns subscription handle; call `close()` to stop receiving events.
 */
export function subscribeEvents(
  onMessage: (event: InspectorEvent) => void,
  onError?: (error: Event) => void,
): EventSubscription;
export function subscribeEvents(handlers: SubscribeHandlers): EventSubscription;
export function subscribeEvents(
  arg: SubscribeHandlers | ((event: InspectorEvent) => void),
  onErrorLegacy?: (error: Event) => void,
): EventSubscription {
  const token = window.__INSPECTOR_AUTH_TOKEN__ ?? '';
  const url = `${EVENTS_URL}?auth=${encodeURIComponent(token)}`;
  const source = new EventSource(url);

  const isLegacy = typeof arg === 'function';
  const handlers: SubscribeHandlers = isLegacy ? { onNotification: arg, onError: onErrorLegacy } : arg;

  source.onmessage = (msg) => {
    let event: InspectorEvent;
    try {
      event = JSON.parse(msg.data) as InspectorEvent;
    } catch {
      event = { type: 'raw', payload: msg.data, timestamp: Date.now() };
    }
    const consumed = dispatchTyped(handlers, event);
    if (!consumed || isLegacy) {
      handlers.onNotification?.(event);
    }
  };

  source.onerror = (err) => {
    handlers.onError?.(err);
  };

  return {
    close: () => source.close(),
  };
}
