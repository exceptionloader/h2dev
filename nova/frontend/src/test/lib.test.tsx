import { describe, expect, it } from "vitest";
import { safeRedirect } from "@/features/auth/LoginPage";
import { describeAudit } from "@/features/servers/AuditLogPage";
import { groupMembers } from "@/features/servers/MemberSidebar";
import { errorMessage } from "@/lib/errors";
import { colorFor, initials } from "@/lib/format";
import { can } from "@/lib/permissions";
import type { AuditLogEntry, Member, Role } from "@/lib/types";

describe("errorMessage", () => {
  it("maps known codes to German text and falls back for unknown ones", () => {
    expect(errorMessage("invalid_credentials")).toMatch(/Passwort ist falsch/);
    expect(errorMessage("something_new")).toBe(errorMessage("internal_error"));
    expect(errorMessage(undefined)).toBe(errorMessage("internal_error"));
  });
});

describe("can", () => {
  it("respects ADMINISTRATOR and explicit permissions", () => {
    expect(can(["MANAGE_SERVER"], "MANAGE_SERVER")).toBe(true);
    expect(can(["SEND_MESSAGES"], "MANAGE_SERVER")).toBe(false);
    expect(can(["ADMINISTRATOR"], "MANAGE_ROLES")).toBe(true);
    expect(can(undefined, "VIEW_CHANNEL")).toBe(false);
  });
});

describe("safeRedirect", () => {
  it("only allows relative in-app paths", () => {
    expect(safeRedirect("/server/1")).toBe("/server/1");
    expect(safeRedirect("//evil.example")).toBe("/");
    expect(safeRedirect("https://evil.example")).toBe("/");
    expect(safeRedirect(null)).toBe("/");
  });
});

describe("format helpers", () => {
  it("builds initials and stable colors", () => {
    expect(initials("Valorant Crew")).toBe("VC");
    expect(initials("jakob")).toBe("JA");
    expect(colorFor("abc")).toBe(colorFor("abc"));
  });
});

const role = (id: string, position: number, is_default = false): Role => ({
  id, name: id, color: null, icon: null, position, permissions: [], is_default, managed: false,
});
const member = (id: string, role_ids: string[], is_owner = false): Member => ({
  id,
  user: { id, username: id, display_name: id, avatar_url: null, status: "online", custom_status: null, is_bot: false, presence: "online" },
  nickname: null, joined_at: "2026-01-01T00:00:00Z", role_ids, is_owner,
});

describe("groupMembers", () => {
  it("groups by owner, then highest role, then default role", () => {
    const roles = [role("member", 0, true), role("mod", 1), role("admin", 2)];
    const groups = groupMembers(
      [member("o", [], true), member("a", ["mod", "admin"]), member("m", ["mod"]), member("p", [])],
      roles,
    );
    expect(groups.map((g) => [g.label, g.members.map((m) => m.id)])).toEqual([
      ["Owner", ["o"]],
      ["admin", ["a"]],
      ["mod", ["m"]],
      ["member", ["p"]],
    ]);
  });
});

describe("describeAudit", () => {
  const entry = (action: string, details: Record<string, unknown>): AuditLogEntry => ({
    id: "1", action, actor: null, target_type: null, target_id: null, details, created_at: "",
  });
  it("renders German descriptions", () => {
    expect(describeAudit(entry("server.create", { name: "Squad" }))).toBe("hat den Server „Squad“ erstellt");
    expect(describeAudit(entry("channel.create", { name: "x", type: "voice", private: true }))).toBe(
      "hat privaten Sprachkanal „x“ erstellt",
    );
    expect(describeAudit(entry("server.update", { name: {}, visibility: {} }))).toBe(
      "hat Name, Sichtbarkeit geändert",
    );
  });
});

describe("RichText", () => {
  it("renders links safely and never as HTML", async () => {
    const { render } = await import("@testing-library/react");
    const { createMemoryRouter, RouterProvider } = await import("react-router");
    const { RichText } = await import("@/features/chat/RichText");
    const router = createMemoryRouter([
      { path: "/", element: <p data-testid="t"><RichText text={'<img src=x onerror=alert(1)> https://nova.gg **fett** /einladung/abc123'} /></p> },
    ]);
    const { getByTestId } = render(<RouterProvider router={router} />);
    const el = getByTestId("t");
    expect(el.querySelector("img")).toBeNull();
    expect(el.textContent).toContain("<img src=x onerror=alert(1)>");
    const links = Array.from(el.querySelectorAll("a"));
    expect(links.map((a) => a.getAttribute("href"))).toEqual(["https://nova.gg", "/einladung/abc123"]);
    expect(links[0]!.getAttribute("rel")).toContain("noopener");
    expect(el.querySelector("strong")?.textContent).toBe("fett");
  });
});
