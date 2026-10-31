export function NovaLogo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" className={className} aria-hidden>
      <rect width="32" height="32" rx="9" fill="#172033" />
      <path d="M16 5l2.6 8.4L27 16l-8.4 2.6L16 27l-2.6-8.4L5 16l8.4-2.6z" fill="#8B5CF6" />
      <circle cx="16" cy="16" r="2.6" fill="#22D3EE" />
    </svg>
  );
}
