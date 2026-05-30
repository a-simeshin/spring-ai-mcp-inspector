/**
 * Placeholder for tabs to be implemented in a later v2 milestone.
 */

export interface PlaceholderTabProps {
  name: string;
  milestone?: string;
}

export function PlaceholderTab({ name, milestone = 'v2.4' }: PlaceholderTabProps) {
  return (
    <section className="space-y-2">
      <h2 className="text-xl font-semibold text-foreground">{name}</h2>
      <p className="text-sm italic text-muted-foreground">Available in {milestone}.</p>
    </section>
  );
}
