import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SettingsDialog } from "./settings-dialog";

function openServerSelect() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <SettingsDialog open onOpenChange={vi.fn()} />
    </QueryClientProvider>,
  );
  fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
  // A closed select renders no options, so an absent entry proves nothing
  expect(screen.getAllByRole("option").length).toBeGreaterThan(0);
}

describe("SettingsDialog", () => {
  beforeEach(() => {
    // jsdom has no scrollIntoView, which the open select calls
    Element.prototype.scrollIntoView = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("{}")),
    );
  });

  afterEach(() => {
    delete window.APP_CONFIG;
    vi.unstubAllGlobals();
  });

  it("offers a custom URL in thin mode", () => {
    openServerSelect();
    expect(
      screen.getByRole("option", { name: "Custom URL..." }),
    ).toBeInTheDocument();
  });

  it("hides the custom URL when the proxy has passthrough off", () => {
    window.APP_CONFIG = { proxy: { passthrough: false } };
    openServerSelect();
    expect(screen.queryByRole("option", { name: "Custom URL..." })).toBeNull();
  });
});
