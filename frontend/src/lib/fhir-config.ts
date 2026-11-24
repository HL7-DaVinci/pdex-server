export interface FhirServer {
  name: string;
  url: string;
}

export interface CdsServer {
  name: string;
  url: string;
}

/** Present when this server proxies requests for other servers. */
export interface ProxyConfig {
  passthrough: boolean;
}

interface AppConfig {
  fhirServers?: FhirServer[];
  cdsServers?: CdsServer[];
  proxy?: ProxyConfig;
  /** Present when this server offers sign-in at the authorization servers. */
  login?: {
    /** True when this server lists its Practitioners and Patients for the sign-in. */
    people?: boolean;
  };
}

/**
 * Marks a request that the app itself sends. A page of another origin cannot
 * set this header without a CORS preflight.
 */
export const APP_REQUEST_HEADER = "X-App-Request";

declare global {
  interface Window {
    APP_CONFIG?: AppConfig;
  }
}

const DEFAULT_FHIR_SERVERS: FhirServer[] = [
  {
    name: "Local Server",
    url: "http://localhost:8080/fhir",
  },
];

function isValidFhirServer(server: unknown): server is FhirServer {
  return (
    typeof server === "object" &&
    server !== null &&
    typeof (server as FhirServer).name === "string" &&
    typeof (server as FhirServer).url === "string"
  );
}

/** The bundled server first, then each listed server once. */
function parseProxyModeServers(fhirServers: unknown): FhirServer[] {
  const servers: FhirServer[] = [
    { name: "This server", url: `${window.location.origin}/fhir` },
  ];
  if (Array.isArray(fhirServers)) {
    for (const server of fhirServers.filter(isValidFhirServer)) {
      // Absolute URLs let stored headers match the absolute paging links of HAPI
      const url = /^\/(?!\/)/.test(server.url)
        ? `${window.location.origin}${server.url}`
        : server.url;
      if (!servers.some((listed) => listed.url === url)) {
        servers.push({ ...server, url });
      }
    }
  }
  return servers;
}

function parseFhirServers(): FhirServer[] {
  if (window?.APP_CONFIG?.proxy) {
    return parseProxyModeServers(window.APP_CONFIG.fhirServers);
  }

  if (
    window?.APP_CONFIG?.fhirServers &&
    Array.isArray(window.APP_CONFIG.fhirServers)
  ) {
    const servers = window.APP_CONFIG.fhirServers.filter(isValidFhirServer);
    if (servers.length > 0) {
      return servers;
    }
  }

  const envServers = import.meta.env.VITE_FHIR_SERVERS;
  if (envServers) {
    try {
      const parsed = JSON.parse(envServers);
      if (Array.isArray(parsed)) {
        const servers = parsed.filter(isValidFhirServer);
        if (servers.length > 0) {
          return servers;
        }
      }
    } catch {
      console.warn("Failed to parse VITE_FHIR_SERVERS, using defaults");
    }
  }

  return DEFAULT_FHIR_SERVERS;
}

export const FHIR_SERVERS: FhirServer[] = parseFhirServers();

// A server choice from thin mode has no meaning in proxy mode, so proxy mode keeps its own key
const STORAGE_KEY = window?.APP_CONFIG?.proxy
  ? "fhir-proxy-server-url"
  : "fhir-server-url";

export function getStoredServerUrl(): string {
  if (typeof window === "undefined") {
    return FHIR_SERVERS[0].url;
  }
  const stored = localStorage.getItem(STORAGE_KEY);
  if (!stored || (!isCustomServerAllowed() && !getServerByUrl(stored))) {
    return FHIR_SERVERS[0].url;
  }
  return stored;
}

export function setStoredServerUrl(url: string): void {
  if (typeof window !== "undefined") {
    localStorage.setItem(STORAGE_KEY, url);
  }
}

export function getServerByUrl(url: string): FhirServer | undefined {
  return FHIR_SERVERS.find((server) => server.url === url);
}

/** The proxy refuses a server that is not listed unless passthrough is on. */
export function isCustomServerAllowed(): boolean {
  return window.APP_CONFIG?.proxy?.passthrough !== false;
}

const URL_SCHEME = /^[a-z][a-z\d+.-]*:/i;

/**
 * The URL to request for `url`. In proxy mode a URL of another origin goes
 * through the proxy of this server, which decides if the destination is
 * permitted.
 */
export function toRequestUrl(url: string): string {
  if (!window.APP_CONFIG?.proxy) {
    return url;
  }
  const resolved = new URL(url, window.location.origin);
  if (resolved.origin === window.location.origin) {
    return url;
  }
  // A URL with no scheme, such as "//host/fhir", takes the scheme of the page
  const destination = URL_SCHEME.test(url) ? url : resolved.href;
  return `/proxy?url=${encodeURIComponent(destination)}`;
}

/**
 * The `server` value of the `/auth` requests for `url`: `/fhir` for This
 * server and the absolute URL for a server of another origin. Another path of
 * the page origin has no sign-in.
 */
export function toAuthServer(url: string): string | undefined {
  const { origin } = window.location;
  if (url === `${origin}/fhir`) {
    return "/fhir";
  }
  try {
    const resolved = new URL(url, origin);
    if (resolved.origin === origin) {
      return undefined;
    }
    // A URL with no scheme, such as "//host/fhir", takes the scheme of the page
    return URL_SCHEME.test(url) ? url : resolved.href;
  } catch {
    // A malformed custom URL has no sign-in
    return undefined;
  }
}

export interface CustomHeader {
  name: string;
  value: string;
}

/** Hop-by-hop and proxy headers the browser owns, a request cannot set them. */
export const DISALLOWED_HEADER_NAMES = new Set([
  "host",
  "connection",
  "content-length",
  "transfer-encoding",
  "keep-alive",
  "upgrade",
  "te",
  "trailer",
  "expect",
  "proxy-authorization",
  "proxy-authenticate",
]);

/** RFC 7230 token rule, the character set `Headers.set()` accepts for a header name. */
const HEADER_NAME_PATTERN = /^[A-Za-z0-9!#$%&'*+\-.^_`|~]+$/;

export function isValidHeaderName(name: string): boolean {
  return HEADER_NAME_PATTERN.test(name);
}

/** `Headers.set()` rejects CR, LF, and NUL in a header value. */
export function hasInvalidHeaderValueChars(value: string): boolean {
  return /[\r\n\0]/.test(value);
}

/** Keeps the well-formed, unique entries of a stored header list. */
export function sanitizeCustomHeaders(value: unknown): CustomHeader[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const seen = new Set<string>();
  const headers: CustomHeader[] = [];
  for (const entry of value) {
    if (typeof entry !== "object" || entry === null) continue;
    const { name, value: headerValue } = entry as Partial<CustomHeader>;
    if (typeof name !== "string" || typeof headerValue !== "string") continue;
    const trimmed = name.trim();
    const lower = trimmed.toLowerCase();
    if (
      !lower ||
      DISALLOWED_HEADER_NAMES.has(lower) ||
      seen.has(lower) ||
      !isValidHeaderName(trimmed) ||
      hasInvalidHeaderValueChars(headerValue)
    ) {
      continue;
    }
    seen.add(lower);
    headers.push({ name: trimmed, value: headerValue });
  }
  return headers;
}

const SERVER_HEADERS_STORAGE_KEY = "fhir-server-headers";

/** The object stored as JSON under `key`, with one entry for each server. */
export function readStoredRecord(key: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(localStorage.getItem(key) ?? "{}");
    if (typeof parsed === "object" && parsed !== null) {
      return parsed as Record<string, unknown>;
    }
  } catch {
    // A corrupt record reads as empty
  }
  return {};
}

/** Custom headers sent with every request to the server at `url`, kept per server. */
export function getStoredServerHeaders(url: string): CustomHeader[] {
  if (typeof window === "undefined") {
    return [];
  }
  return sanitizeCustomHeaders(
    readStoredRecord(SERVER_HEADERS_STORAGE_KEY)[url],
  );
}

/** Stores the headers for `url` and reports whether the stored value changed. */
export function setStoredServerHeaders(
  url: string,
  headers: CustomHeader[],
): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  if (JSON.stringify(headers) === JSON.stringify(getStoredServerHeaders(url))) {
    return false;
  }
  const record = readStoredRecord(SERVER_HEADERS_STORAGE_KEY);
  if (headers.length > 0) {
    record[url] = headers;
  } else {
    delete record[url];
  }
  localStorage.setItem(SERVER_HEADERS_STORAGE_KEY, JSON.stringify(record));
  return true;
}
