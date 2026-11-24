import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  type FhirError,
  fhirRequestHeaders,
  retryOnceUnlessClientError,
  useCapabilityStatement,
  useServerStatus,
} from "./use-fhir-api";

function fhirError(status?: number): FhirError {
  return Object.assign(new Error("failed"), { status });
}

describe("retryOnceUnlessClientError", () => {
  it("retries a server error or a network failure once", () => {
    for (const error of [fhirError(500), fhirError(503), fhirError()]) {
      expect(retryOnceUnlessClientError(0, error)).toBe(true);
      expect(retryOnceUnlessClientError(1, error)).toBe(false);
    }
  });

  it("does not retry a client error", () => {
    for (const status of [400, 401, 403, 404, 410]) {
      expect(retryOnceUnlessClientError(0, fhirError(status))).toBe(false);
    }
  });

  it("retries a timeout or a rate limit once", () => {
    for (const status of [408, 429]) {
      expect(retryOnceUnlessClientError(0, fhirError(status))).toBe(true);
      expect(retryOnceUnlessClientError(1, fhirError(status))).toBe(false);
    }
  });
});

describe("fhirRequestHeaders", () => {
  const server = "http://localhost:8080/fhir";

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem("fhir-server-url", server);
    localStorage.setItem(
      "fhir-server-headers",
      JSON.stringify({ [server]: [{ name: "X-Api-Key", value: "k" }] }),
    );
  });

  it("sends the stored headers to the active server, page links included", () => {
    for (const url of [
      server,
      `${server}/metadata`,
      `${server}?_getpages=abc&_getpagesoffset=50`,
      `${server}/Patient?_count=10`,
    ]) {
      const headers = fhirRequestHeaders(url);
      expect(headers.get("X-Api-Key")).toBe("k");
      expect(headers.get("Accept")).toBe("application/fhir+json");
    }
  });

  it("does not send them to another host or a sibling path", () => {
    for (const url of [
      "http://other.example/fhir/metadata",
      `${server}-other/metadata`,
    ]) {
      expect(fhirRequestHeaders(url).get("X-Api-Key")).toBeNull();
    }
  });

  it("matches a server url stored with a trailing slash", () => {
    localStorage.setItem("fhir-server-url", `${server}/`);
    localStorage.setItem(
      "fhir-server-headers",
      JSON.stringify({ [`${server}/`]: [{ name: "X-Api-Key", value: "k" }] }),
    );
    expect(fhirRequestHeaders(`${server}/metadata`).get("X-Api-Key")).toBe("k");
  });

  it("sends them to an absolute page link when the server url is a path", () => {
    localStorage.setItem("fhir-server-url", "/fhir");
    localStorage.setItem(
      "fhir-server-headers",
      JSON.stringify({ "/fhir": [{ name: "X-Api-Key", value: "k" }] }),
    );
    const { origin } = window.location;
    for (const url of ["/fhir/Patient", `${origin}/fhir?_getpages=abc`]) {
      expect(fhirRequestHeaders(url).get("X-Api-Key")).toBe("k");
    }
    expect(
      fhirRequestHeaders(`${origin}/fhir-other/metadata`).get("X-Api-Key"),
    ).toBeNull();
  });

  it("sends no X-App-Request to another origin in thin mode", () => {
    expect(fhirRequestHeaders(`${server}/metadata`).has("X-App-Request")).toBe(
      false,
    );
  });
});

describe("metadata query", () => {
  it("serves the status and the capability statement from one request", async () => {
    const capability = {
      resourceType: "CapabilityStatement",
      fhirVersion: "4.0.1",
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify(capability), { status: 200 }),
      );
    vi.stubGlobal("fetch", fetchMock);
    const queryClient = new QueryClient();
    const wrapper = ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children);
    const server = "http://localhost:8080/fhir";

    const { result } = renderHook(
      () => ({
        status: useServerStatus(server),
        capability: useCapabilityStatement(server),
      }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.status.isConnected).toBe(true));
    expect(result.current.capability.data).toEqual(capability);
    expect(result.current.status.latency).toBeGreaterThanOrEqual(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    vi.unstubAllGlobals();
  });
});

describe("fhirFetch in proxy mode", () => {
  const payer = "https://payer.example.org/fhir";
  const thisServer = `${window.location.origin}/fhir`;
  const fetchMock = vi.fn();

  // FHIR_SERVERS is computed when the module loads, so the config needs a fresh module
  async function loadApi() {
    window.APP_CONFIG = {
      fhirServers: [{ name: "Payer", url: payer }],
      proxy: { passthrough: false },
    };
    vi.resetModules();
    return import("./use-fhir-api");
  }

  function storeActiveServer(url: string) {
    localStorage.setItem("fhir-proxy-server-url", url);
    localStorage.setItem(
      "fhir-server-headers",
      JSON.stringify({ [url]: [{ name: "X-Api-Key", value: "k" }] }),
    );
  }

  beforeEach(() => {
    localStorage.clear();
    fetchMock.mockReset();
    fetchMock.mockImplementation(async () => new Response("{}"));
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    delete window.APP_CONFIG;
    vi.unstubAllGlobals();
  });

  it("requests the proxy URL and attaches headers by destination", async () => {
    storeActiveServer(payer);
    const { fhirFetch } = await loadApi();
    const url = `${payer}/Patient?name=x y`;

    await fhirFetch(url);
    await fhirFetch("https://other.example/fhir/metadata");

    const [[requestUrl, init], [, otherInit]] = fetchMock.mock.calls;
    expect(requestUrl).toBe(`/proxy?url=${encodeURIComponent(url)}`);
    expect(init.headers.get("X-Api-Key")).toBe("k");
    expect(otherInit.headers.get("X-Api-Key")).toBeNull();
  });

  it("sends X-App-Request to the page origin, through the proxy too", async () => {
    const { fhirFetch } = await loadApi();

    await fhirFetch(`${thisServer}/Patient`);
    await fhirFetch(`${payer}/Patient`);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    for (const [, init] of fetchMock.mock.calls) {
      expect(init.headers.get("X-App-Request")).toBe("1");
    }
  });

  it("attaches stored headers to a paging link of This server", async () => {
    storeActiveServer(thisServer);
    const { fhirFetch } = await loadApi();
    const pageLink = `${thisServer}?_getpages=abc&_getpagesoffset=50`;

    await fhirFetch(pageLink);

    const [[requestUrl, init]] = fetchMock.mock.calls;
    expect(requestUrl).toBe(pageLink);
    expect(init.headers.get("X-Api-Key")).toBe("k");
  });

  it("uses a plain-text error body as the message", async () => {
    const { fhirFetch } = await loadApi();
    for (const contentType of ["text/plain", "text/plain;charset=UTF-8"]) {
      fetchMock.mockResolvedValueOnce(
        new Response("Destination is not a listed server", {
          status: 403,
          headers: { "Content-Type": contentType },
        }),
      );
      await expect(
        fhirFetch("https://other.example/fhir/metadata"),
      ).rejects.toMatchObject({
        status: 403,
        message: "Destination is not a listed server",
      });
    }
  });

  it("keeps the status message for a response that does not come from the proxy", async () => {
    const { fhirFetch } = await loadApi();
    fetchMock.mockResolvedValueOnce(
      new Response("Forbidden", {
        status: 403,
        headers: { "Content-Type": "text/plain" },
      }),
    );
    await expect(fhirFetch(`${thisServer}/metadata`)).rejects.toMatchObject({
      status: 403,
      message: expect.stringMatching(/^FHIR request failed: 403/),
    });
  });
});
