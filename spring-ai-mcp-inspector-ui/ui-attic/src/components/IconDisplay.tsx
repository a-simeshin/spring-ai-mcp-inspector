// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { Box, FileText, Wand2, Hammer, type LucideIcon } from 'lucide-react';
import clsx from 'clsx';

export type CapabilityKind = 'tool' | 'resource' | 'prompt' | 'generic';

const ICONS: Record<CapabilityKind, LucideIcon> = {
  tool: Hammer,
  resource: FileText,
  prompt: Wand2,
  generic: Box,
};

export interface IconDisplayProps {
  kind?: CapabilityKind;
  /** Optional list of icon image overrides (e.g. from MCP `icons` field). */
  icons?: Array<{ src: string }>;
  size?: number;
  className?: string;
}

/** Renders an MCP capability icon, falling back to a lucide glyph. */
export function IconDisplay({ kind = 'generic', icons, size = 16, className }: IconDisplayProps) {
  if (icons && icons.length > 0) {
    const first = icons[0];
    return (
      <img
        src={first.src}
        alt=""
        width={size}
        height={size}
        className={clsx('inline-block object-contain', className)}
        onError={(e) => {
          e.currentTarget.style.display = 'none';
        }}
      />
    );
  }
  const Icon = ICONS[kind] ?? Box;
  return <Icon size={size} className={clsx('inline-block text-muted-foreground', className)} />;
}

export default IconDisplay;
