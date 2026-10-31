import { useOutletContext } from "react-router";
import type { ServerDetail } from "@/lib/types";

/** The server loaded by ServerLayout. */
export const useServerContext = () => useOutletContext<ServerDetail>();
