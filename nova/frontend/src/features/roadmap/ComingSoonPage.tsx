import type { LucideIcon } from "lucide-react";
import { EmptyState, PageHeader } from "@/components/ui/Feedback";

interface Props {
  title: string;
  icon: LucideIcon;
  milestone: string;
  description: string;
}

/** Honest placeholder for navigation targets that are planned but not built yet. */
export function ComingSoonPage({ title, icon, milestone, description }: Props) {
  return (
    <div className="flex min-w-0 flex-1 flex-col">
      <PageHeader icon={icon} title={title} />
      <div className="flex flex-1 items-center justify-center p-6">
        <EmptyState icon={icon} title={`${title} – in Entwicklung`}>
          <p>{description}</p>
          <p className="mt-3 inline-block rounded-full bg-primary/15 px-3 py-1 text-xs font-medium text-primary">
            Geplant für {milestone}
          </p>
        </EmptyState>
      </div>
    </div>
  );
}
