import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

const control =
  "w-full rounded-lg border border-line bg-base px-3 text-sm text-fg placeholder:text-muted/60 " +
  "transition-colors hover:border-line-strong focus:border-primary focus:outline-none " +
  "disabled:opacity-60";

interface WrapperProps {
  label: string;
  hint?: ReactNode;
  error?: string;
  children: (id: string) => ReactNode;
}

function FieldWrapper({ label, hint, error, children }: WrapperProps) {
  const id = useId();
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-xs font-semibold uppercase tracking-wide text-muted">
        {label}
      </label>
      {children(id)}
      {error ? (
        <p className="text-xs text-danger" role="alert">
          {error}
        </p>
      ) : hint ? (
        <p className="text-xs text-muted">{hint}</p>
      ) : null}
    </div>
  );
}

type InputProps = InputHTMLAttributes<HTMLInputElement> & Omit<WrapperProps, "children">;

export function TextField({ label, hint, error, className, ...rest }: InputProps) {
  return (
    <FieldWrapper label={label} hint={hint} error={error}>
      {(id) => (
        <input
          id={id}
          aria-invalid={error ? true : undefined}
          className={cn(control, "h-10", error && "border-danger", className)}
          {...rest}
        />
      )}
    </FieldWrapper>
  );
}

type TextareaProps = TextareaHTMLAttributes<HTMLTextAreaElement> & Omit<WrapperProps, "children">;

export function TextAreaField({ label, hint, error, className, ...rest }: TextareaProps) {
  return (
    <FieldWrapper label={label} hint={hint} error={error}>
      {(id) => (
        <textarea
          id={id}
          className={cn(control, "min-h-20 resize-y py-2", error && "border-danger", className)}
          {...rest}
        />
      )}
    </FieldWrapper>
  );
}

type SelectProps = SelectHTMLAttributes<HTMLSelectElement> & Omit<WrapperProps, "children">;

export function SelectField({ label, hint, error, className, children, ...rest }: SelectProps) {
  return (
    <FieldWrapper label={label} hint={hint} error={error}>
      {(id) => (
        <select id={id} className={cn(control, "h-10", className)} {...rest}>
          {children}
        </select>
      )}
    </FieldWrapper>
  );
}

export function FormError({ message }: { message?: string | null }) {
  if (!message) return null;
  return (
    <div role="alert" className="rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
      {message}
    </div>
  );
}
