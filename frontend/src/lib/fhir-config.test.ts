import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  headerEditorError,
  validCustomHeaders,
} from "@/components/header-editor";
import {
  getStoredServerHeaders,
  sanitizeCustomHeaders,
  setStoredServerHeaders,
  toAuthServer,
} from "./fhir-config";

const origin = window.location.origin;
const payer = "https://payer.example.org/fhir";

// FHIR_SERVERS is computed when the module loads, so each config gets a fresh module
async function loadConfig(appConfig: Window["APP_CONFIG"]) {
  window.APP_CONFIG = appConfig;
  vi.resetModules();
  return import("./fhir-config");
}

afterEach(() => {
  delete window.APP_CONFIG;
  vi.unstubAllEnvs();
  localStorage.clear();
});

describe("toRequestUrl", () => {
  it("returns the URL when APP_CONFIG has no proxy", async () => {
    const { toRequestUrl } = await loadConfig({ fhirServers: [] });
    expect(toRequestUrl(`${payer}/Patient`)).toBe(`${payer}/Patient`);
  });

  it("returns a same-origin or relative URL without change", async () => {
    const { toRequestUrl } = await loadConfig({
      proxy: { passthrough: false },
    });
    for (const url of [`${origin}/fhir/Patient`, "/fhir/Patient"]) {
      expect(toRequestUrl(url)).toBe(url);
    }
  });

  it("maps any other URL to /proxy?url=<encoded URL>", async () => {
    const { toRequestUrl } = await loadConfig({
      proxy: { passthrough: false },
    });
    for (const url of [
      "https://a.example/fhir/Patient?name=x y|z",
      "http://localhost:8080/fhir/metadata",
    ]) {
      expect(toRequestUrl(url)).toBe(`/proxy?url=${encodeURIComponent(url)}`);
    }
  });

  it("maps a protocol-relative URL to the absolute URL with the page scheme", async () => {
    // Vitest exposes the jsdom instance, which can move the page to another URL
    const { jsdom } = globalThis as unknown as {
      jsdom: { reconfigure(settings: { url: string }): void };
    };
    jsdom.reconfigure({ url: "https://app.example.org/" });
    try {
      const { toRequestUrl } = await loadConfig({
        proxy: { passthrough: false },
      });
      expect(toRequestUrl("//payer.example/fhir/Patient")).toBe(
        `/proxy?url=${encodeURIComponent("https://payer.example/fhir/Patient")}`,
      );
      expect(toRequestUrl("//app.example.org/fhir/Patient")).toBe(
        "//app.example.org/fhir/Patient",
      );
    } finally {
      jsdom.reconfigure({ url: `${origin}/` });
    }
  });
});

describe("toAuthServer", () => {
  it("sends /fhir for This server, the URL for another origin, and nothing for another path of the page origin", () => {
    expect(toAuthServer(`${origin}/fhir`)).toBe("/fhir");
    expect(toAuthServer(payer)).toBe(payer);
    expect(toAuthServer("//payer.example.org/fhir")).toBe(
      `${window.location.protocol}//payer.example.org/fhir`,
    );
    for (const url of [`${origin}/other/fhir`, "/other/fhir", "http://["]) {
      expect(toAuthServer(url)).toBeUndefined();
    }
  });
});

describe("server list in proxy mode", () => {
  it("starts with This server at <origin>/fhir", async () => {
    const { FHIR_SERVERS } = await loadConfig({
      proxy: { passthrough: false },
    });
    expect(FHIR_SERVERS).toEqual([
      { name: "This server", url: `${origin}/fhir` },
    ]);
  });

  it("adds each fhirServers entry once", async () => {
    const { FHIR_SERVERS } = await loadConfig({
      fhirServers: [
        { name: "Bundled", url: `${origin}/fhir` },
        { name: "Payer", url: payer },
        { name: "Payer again", url: payer },
        { name: "EHR", url: "http://ehr:8080/fhir" },
      ],
      proxy: { passthrough: false },
    });
    expect(FHIR_SERVERS).toEqual([
      { name: "This server", url: `${origin}/fhir` },
      { name: "Payer", url: payer },
      { name: "EHR", url: "http://ehr:8080/fhir" },
    ]);
  });

  it("does not use VITE_FHIR_SERVERS or the localhost default", async () => {
    const envServer = { name: "Env", url: "https://env.example/fhir" };
    vi.stubEnv("VITE_FHIR_SERVERS", JSON.stringify([envServer]));
    expect((await loadConfig({})).FHIR_SERVERS).toEqual([envServer]);

    const { FHIR_SERVERS } = await loadConfig({ proxy: { passthrough: true } });
    expect(FHIR_SERVERS.map((server) => server.url)).toEqual([
      `${origin}/fhir`,
    ]);
  });

  it("does not repeat This server for an entry with the url /fhir", async () => {
    const { FHIR_SERVERS } = await loadConfig({
      fhirServers: [{ name: "Local", url: "/fhir" }],
      proxy: { passthrough: false },
    });
    expect(FHIR_SERVERS).toEqual([
      { name: "This server", url: `${origin}/fhir` },
    ]);
  });

  it("resolves a root-relative entry against the page origin", async () => {
    const { FHIR_SERVERS } = await loadConfig({
      fhirServers: [{ name: "Other", url: "/other/fhir" }],
      proxy: { passthrough: false },
    });
    expect(FHIR_SERVERS).toEqual([
      { name: "This server", url: `${origin}/fhir` },
      { name: "Other", url: `${origin}/other/fhir` },
    ]);
  });
});

describe("server list in thin mode", () => {
  it("keeps a relative entry as given", async () => {
    const { FHIR_SERVERS } = await loadConfig({
      fhirServers: [{ name: "Local", url: "/fhir" }],
    });
    expect(FHIR_SERVERS).toEqual([{ name: "Local", url: "/fhir" }]);
  });
});

describe("getStoredServerUrl in proxy mode", () => {
  it("ignores a stored URL that is not in the list when passthrough is false", async () => {
    const { getStoredServerUrl, setStoredServerUrl } = await loadConfig({
      fhirServers: [{ name: "Payer", url: payer }],
      proxy: { passthrough: false },
    });
    setStoredServerUrl("https://other.example/fhir");
    expect(getStoredServerUrl()).toBe(`${origin}/fhir`);
    setStoredServerUrl(payer);
    expect(getStoredServerUrl()).toBe(payer);
  });

  it("keeps a stored custom URL across reloads when passthrough is true", async () => {
    const appConfig = { proxy: { passthrough: true } };
    (await loadConfig(appConfig)).setStoredServerUrl(
      "https://other.example/fhir",
    );
    const { getStoredServerUrl } = await loadConfig(appConfig);
    expect(getStoredServerUrl()).toBe("https://other.example/fhir");
  });

  it("ignores the thin-mode choice when passthrough is true", async () => {
    localStorage.setItem("fhir-server-url", "http://localhost:8080/fhir");
    const { getStoredServerUrl } = await loadConfig({
      proxy: { passthrough: true },
    });
    expect(getStoredServerUrl()).toBe(`${origin}/fhir`);
  });

  it("ignores the thin-mode choice when passthrough is false", async () => {
    localStorage.setItem("fhir-server-url", "http://localhost:8080/fhir");
    const { getStoredServerUrl } = await loadConfig({
      proxy: { passthrough: false },
    });
    expect(getStoredServerUrl()).toBe(`${origin}/fhir`);
  });
});

describe("stored server URL in thin mode", () => {
  it("uses the fhir-server-url key", async () => {
    const { getStoredServerUrl, setStoredServerUrl } = await loadConfig({});
    setStoredServerUrl("https://thin.example/fhir");
    expect(localStorage.getItem("fhir-server-url")).toBe(
      "https://thin.example/fhir",
    );
    expect(localStorage.getItem("fhir-proxy-server-url")).toBeNull();
    expect(getStoredServerUrl()).toBe("https://thin.example/fhir");
  });
});

describe("isCustomServerAllowed", () => {
  it("is false only in proxy mode with passthrough off", async () => {
    const allowed = async (appConfig: Window["APP_CONFIG"]) =>
      (await loadConfig(appConfig)).isCustomServerAllowed();
    expect(await allowed(undefined)).toBe(true);
    expect(await allowed({ proxy: { passthrough: true } })).toBe(true);
    expect(await allowed({ proxy: { passthrough: false } })).toBe(false);
  });
});

describe("sanitizeCustomHeaders", () => {
  it("keeps well-formed unique headers and trims the name", () => {
    expect(
      sanitizeCustomHeaders([
        { name: " X-Api-Key ", value: "abc" },
        { name: "x-api-key", value: "dup" },
        { name: "Accept", value: "text/plain" },
      ]),
    ).toEqual([
      { name: "X-Api-Key", value: "abc" },
      { name: "Accept", value: "text/plain" },
    ]);
  });

  it("drops malformed entries, hop-by-hop names, and bad values", () => {
    expect(
      sanitizeCustomHeaders([
        null,
        "x",
        { name: 1, value: "a" },
        { name: "Host", value: "evil" },
        { name: "", value: "a" },
        { name: "Bad Name", value: "a" },
        { name: "X-Ok", value: "a\r\nInjected: b" },
      ]),
    ).toEqual([]);
    expect(sanitizeCustomHeaders("nope")).toEqual([]);
  });
});

describe("stored server headers", () => {
  beforeEach(() => localStorage.clear());

  it("keeps headers per server and removes an emptied entry", () => {
    setStoredServerHeaders("http://a/fhir", [{ name: "X-A", value: "1" }]);
    setStoredServerHeaders("http://b/fhir", [{ name: "X-B", value: "2" }]);
    expect(getStoredServerHeaders("http://a/fhir")).toEqual([
      { name: "X-A", value: "1" },
    ]);
    expect(getStoredServerHeaders("http://b/fhir")).toEqual([
      { name: "X-B", value: "2" },
    ]);
    setStoredServerHeaders("http://a/fhir", []);
    expect(getStoredServerHeaders("http://a/fhir")).toEqual([]);
    expect(getStoredServerHeaders("http://b/fhir")).toHaveLength(1);
  });

  it("reports whether the stored headers changed", () => {
    const headers = [{ name: "X-A", value: "1" }];
    expect(setStoredServerHeaders("http://a/fhir", [])).toBe(false);
    expect(setStoredServerHeaders("http://a/fhir", headers)).toBe(true);
    expect(setStoredServerHeaders("http://a/fhir", headers)).toBe(false);
    expect(setStoredServerHeaders("http://a/fhir", [])).toBe(true);
  });

  it("reads a corrupt record as empty", () => {
    localStorage.setItem("fhir-server-headers", "{not json");
    expect(getStoredServerHeaders("http://a/fhir")).toEqual([]);
    localStorage.setItem(
      "fhir-server-headers",
      JSON.stringify({ "http://a/fhir": [{ name: "Host", value: "x" }] }),
    );
    expect(getStoredServerHeaders("http://a/fhir")).toEqual([]);
  });
});

describe("headerEditorError", () => {
  it("accepts valid rows and rows still being typed", () => {
    expect(
      headerEditorError([
        { name: "X-Api-Key", value: "abc" },
        { name: "", value: "" },
      ]),
    ).toBeUndefined();
  });

  it("names the first problem", () => {
    expect(headerEditorError([{ name: "", value: "v" }])).toBe(
      "Header value needs a name",
    );
    expect(headerEditorError([{ name: "Host", value: "v" }])).toMatch(
      /cannot be sent/,
    );
    expect(headerEditorError([{ name: "a b", value: "v" }])).toMatch(
      /not a valid header name/,
    );
    expect(headerEditorError([{ name: "X", value: "a\nb" }])).toMatch(
      /invalid value/,
    );
    expect(
      headerEditorError([
        { name: "X", value: "1" },
        { name: "x", value: "2" },
      ]),
    ).toMatch(/Duplicate/);
  });
});

describe("validCustomHeaders", () => {
  it("drops nameless rows and trims names", () => {
    expect(
      validCustomHeaders([
        { name: " X ", value: "1" },
        { name: "", value: "" },
      ]),
    ).toEqual([{ name: "X", value: "1" }]);
  });
});
