import { Loader2 } from "lucide-react";
import type { ButtonHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

type Variant = "primary" | "secondary" | "ghost" | "danger";

const VARIANTS: Record<Variant, string> = {
  primary: "bg-primary text-white hover:bg-primary-hover",
  secondary: "bg-card text-fg border border-line hover:bg-card-hover hover:border-line-strong",
  ghost: "text-muted hover:text-fg hover:bg-card",
  danger: "bg-danger/90 text-white hover:bg-danger",
};

export function buttonStyles(variant: Variant = "primary", size: "sm" | "md" = "md"): string {
  return cn(
    "inline-flex items-center justify-center gap-2 rounded-lg font-medium transition-colors",
    "disabled:cursor-not-allowed disabled:opacity-50",
    size === "md" ? "h-10 px-4 text-sm" : "h-8 px-3 text-xs",
    VARIANTS[variant],
  );
}

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: "sm" | "md";
  loading?: boolean;
}

export function Button({
  variant = "primary",
  size = "md",
  loading,
  className,
  children,
  disabled,
  type = "button",
  ...rest
}: Props) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      className={cn(buttonStyles(variant, size), className)}
      {...rest}
    >
      {loading && <Loader2 className="size-4 animate-spin" aria-hidden />}
      {children}
    </button>
  );
}
