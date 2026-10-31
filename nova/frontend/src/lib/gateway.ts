/** WebSocket connection to the NOVA gateway with automatic reconnect. */
export interface GatewayEvent {
  type: string;
  data: any; // eslint-disable-line @typescript-eslint/no-explicit-any
}

export type GatewayStatus = "connecting" | "open" | "closed";

export class GatewayClient {
  private ws: WebSocket | null = null;
  private retry = 0;
  private stopped = false;
  private pingTimer: ReturnType<typeof setInterval> | undefined;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;

  constructor(
    private readonly onEvent: (event: GatewayEvent) => void,
    private readonly onStatus: (status: GatewayStatus) => void,
  ) {}

  start(): void {
    this.stopped = false;
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    clearInterval(this.pingTimer);
    clearTimeout(this.retryTimer);
    this.ws?.close();
    this.ws = null;
  }

  private connect(): void {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const ws = new WebSocket(`${proto}://${location.host}/api/v1/gateway`);
    this.ws = ws;
    this.onStatus("connecting");

    ws.onopen = () => {
      this.retry = 0;
      this.onStatus("open");
      this.pingTimer = setInterval(() => ws.readyState === WebSocket.OPEN && ws.send("ping"), 25_000);
    };
    ws.onmessage = (msg) => {
      try {
        this.onEvent(JSON.parse(msg.data as string) as GatewayEvent);
      } catch {
        /* ignore malformed frames */
      }
    };
    ws.onclose = (ev) => {
      clearInterval(this.pingTimer);
      this.onStatus("closed");
      // 4401: not authenticated / session revoked → do not hammer the server.
      if (this.stopped || ev.code === 4401 || ev.code === 4001) return;
      const delay = Math.min(30_000, 1000 * 2 ** this.retry++) + Math.random() * 500;
      this.retryTimer = setTimeout(() => this.connect(), delay);
    };
  }
}
