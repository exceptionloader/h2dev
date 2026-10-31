import { motion } from "motion/react";
import type { ReactNode } from "react";
import { NovaLogo } from "@/components/layout/NovaLogo";

export function AuthLayout({
  title,
  subtitle,
  children,
  footer,
}: {
  title: string;
  subtitle: string;
  children: ReactNode;
  footer?: ReactNode;
}) {
  return (
    <div className="flex min-h-full items-center justify-center p-6">
      <div className="pointer-events-none fixed inset-0 bg-[radial-gradient(ellipse_at_top,rgb(139_92_246/0.14),transparent_60%)]" />
      <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.25 }}
        className="relative w-full max-w-sm"
      >
        <div className="mb-8 flex items-center justify-center gap-3">
          <NovaLogo className="size-10" />
          <span className="text-2xl font-bold tracking-[0.2em]">NOVA</span>
        </div>
        <div className="rounded-2xl border border-line bg-surface p-7 shadow-2xl shadow-black/40">
          <h1 className="text-xl font-semibold">{title}</h1>
          <p className="mt-1 mb-6 text-sm text-muted">{subtitle}</p>
          {children}
        </div>
        {footer && <div className="mt-5 text-center text-sm text-muted">{footer}</div>}
      </motion.div>
    </div>
  );
}
