import { afterEach, describe, expect, it, vi } from "vitest";
import { GatewayClient } from "@/lib/gateway";

class FakeWS {
  static instances: FakeWS[] = [];
  static OPEN = 1;
  readyState = 0;
  onopen?: () => void;
  onmessage?: (m: { data: string }) => void;
  onclose?: (e: { code: number }) => void;
  sent: string[] = [];
  constructor(public url: string) {
    FakeWS.instances.push(this);
  }
  send(d: string) {
    this.sent.push(d);
  }
  close() {}
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  FakeWS.instances = [];
});

describe("GatewayClient", () => {
  it("dispatches events and reconnects with backoff, but not after 4401", () => {
    vi.useFakeTimers();
    vi.stubGlobal("WebSocket", FakeWS);
    const events: string[] = [];
    const statuses: string[] = [];
    const client = new GatewayClient((e) => events.push(e.type), (s) => statuses.push(s));
    client.start();
    const ws = FakeWS.instances[0]!;
    expect(ws.url).toMatch(/\/api\/v1\/gateway$/);
    ws.readyState = 1;
    ws.onopen!();
    ws.onmessage!({ data: JSON.stringify({ type: "message.created", data: {} }) });
    ws.onmessage!({ data: "not json" });
    expect(events).toEqual(["message.created"]);

    ws.onclose!({ code: 1006 });
    vi.advanceTimersByTime(2000);
    expect(FakeWS.instances).toHaveLength(2);

    FakeWS.instances[1]!.onclose!({ code: 4401 });
    vi.advanceTimersByTime(60_000);
    expect(FakeWS.instances).toHaveLength(2);
    expect(statuses).toContain("open");
    client.stop();
  });
});
