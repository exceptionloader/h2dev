import { Fragment } from "react";
import { Link } from "react-router";

const TOKEN = /(https?:\/\/[^\s<]+[^\s<.,:;"')\]!?]|\/(?:einladung|sessions)\/[\w-]+|\*\*[^*\n]+\*\*)/g;

/**
 * Plain text with safe links: http(s) URLs open in a new tab, in-app paths
 * (/einladung/…, /sessions/…) navigate inside NOVA, **bold** is rendered.
 * Everything is rendered as React text nodes — never as HTML.
 */
export function RichText({ text }: { text: string }) {
  const parts = text.split(TOKEN);
  return (
    <>
      {parts.map((part, i) => {
        if (i % 2 === 0) return <Fragment key={i}>{part}</Fragment>;
        if (part.startsWith("**")) return <strong key={i}>{part.slice(2, -2)}</strong>;
        if (part.startsWith("/")) {
          return (
            <Link key={i} to={part} className="text-accent hover:underline">
              {part}
            </Link>
          );
        }
        return (
          <a key={i} href={part} target="_blank" rel="noopener noreferrer nofollow" className="text-accent hover:underline">
            {part}
          </a>
        );
      })}
    </>
  );
}
