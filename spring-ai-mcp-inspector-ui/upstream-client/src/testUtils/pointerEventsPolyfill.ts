// jsdom (via jest-fixed-jsdom) does not implement PointerEvent, and React 18
// only routes pointer-event handlers when the constructor exists. Radix
// Tooltip opens on `pointermove`, so tests that hover triggers need a legal
// PointerEvent. Must be imported before react-dom so React attaches the
// pointermove listeners at all.
if (typeof globalThis.PointerEvent === "undefined") {
  // @ts-expect-error - jsdom gap; this file is test-only.
  globalThis.PointerEvent = class PointerEvent extends MouseEvent {
    pointerId: number;
    pointerType: string;
    isPrimary: boolean;

    constructor(type: string, params: PointerEventInit = {}) {
      super(type, params);
      this.pointerId = params.pointerId ?? 1;
      this.pointerType = params.pointerType ?? "mouse";
      this.isPrimary = params.isPrimary ?? true;
    }
  };
}
