import { X } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useNavigate } from "react-router";
import { cn } from "@/lib/cn";
import { useToasts } from "@/stores/toasts";

export function Toaster() {
  const toasts = useToasts((s) => s.toasts);
  const dismiss = useToasts((s) => s.dismiss);
  const navigate = useNavigate();
  return (
    <div className="pointer-events-none fixed right-4 bottom-20 z-[60] flex w-80 flex-col gap-2" aria-live="polite">
      <AnimatePresence>
        {toasts.map((t) => (
          <motion.div
            key={t.id}
            initial={{ opacity: 0, x: 24 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: 24 }}
            className={cn(
              "pointer-events-auto flex items-start gap-3 rounded-xl border bg-surface p-3 shadow-xl shadow-black/40",
              t.tone === "error" ? "border-danger/40" : t.tone === "success" ? "border-success/40" : "border-line-strong",
            )}
            role="status"
          >
            <button
              className="min-w-0 flex-1 text-left"
              onClick={() => {
                if (t.href) navigate(t.href);
                dismiss(t.id);
              }}
            >
              <div className="truncate text-sm font-semibold">{t.title}</div>
              {t.body && <div className="mt-0.5 line-clamp-2 text-xs text-muted">{t.body}</div>}
            </button>
            <button onClick={() => dismiss(t.id)} className="text-muted hover:text-fg" aria-label="Schließen">
              <X className="size-4" />
            </button>
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
}
