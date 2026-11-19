import * as React from "react";
import * as TabsPrimitive from "@radix-ui/react-tabs";

import { cn } from "@/lib/utils";

const Tabs = TabsPrimitive.Root;

const TabsList = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.List>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.List>
>(({ className, ...props }, ref) => (
  // [spring-ai-mcp-inspector PATCH] demo E2E contract (#378): tab-list anchor, was [role=tablist].
  <TabsPrimitive.List
    ref={ref}
    data-testid="tab-list"
    className={cn(
      "inline-flex h-9 items-center justify-center rounded-lg bg-muted p-1 text-muted-foreground",
      className,
    )}
    {...props}
  />
));
TabsList.displayName = TabsPrimitive.List.displayName;

const TabsTrigger = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.Trigger>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.Trigger>
>(({ className, ...props }, ref) => (
  // [spring-ai-mcp-inspector PATCH] demo E2E contract (#378): tab-trigger-<value> anchor, was [role=tab][id$="-trigger-<value>"].
  // [spring-ai-mcp-inspector PATCH] px-2 instead of the upstream px-3 (#120): the
  // inspector tab bar wraps (no overflow) and carries 14 triggers; at the 780x437
  // issue #60 repro viewport the wider padding pushed the bar to a third row and
  // moved the Tools tab "List Tools" button centre below the fold, where
  // elementFromPoint can no longer return the button (InspectorUiIT
  // ResponsiveHistoryLayout). The 4px-per-side cut keeps every trigger label.
  <TabsPrimitive.Trigger
    ref={ref}
    data-testid={props.value !== undefined ? `tab-trigger-${props.value}` : undefined}
    className={cn(
      "inline-flex items-center justify-center whitespace-nowrap rounded-md px-2 py-1 text-sm font-medium ring-offset-background transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 bg-muted data-[state=active]:bg-background data-[state=active]:text-foreground data-[state=active]:shadow",
      className,
    )}
    {...props}
  />
));
TabsTrigger.displayName = TabsPrimitive.Trigger.displayName;

const TabsContent = React.forwardRef<
  React.ElementRef<typeof TabsPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof TabsPrimitive.Content>
>(({ className, ...props }, ref) => (
  // [spring-ai-mcp-inspector PATCH] demo E2E contract (#378): tab-content-<value> anchor; Radix mounts only the active content, so [data-testid^=tab-content-] is the active panel, was [role=tabpanel][data-state=active].
  <TabsPrimitive.Content
    ref={ref}
    data-testid={props.value !== undefined ? `tab-content-${props.value}` : undefined}
    className={cn(
      "mt-2 ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
      className,
    )}
    {...props}
  />
));
TabsContent.displayName = TabsPrimitive.Content.displayName;

export { Tabs, TabsList, TabsTrigger, TabsContent };
