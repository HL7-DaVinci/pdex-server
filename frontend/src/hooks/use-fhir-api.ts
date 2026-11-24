import { queryOptions, useQuery } from "@tanstack/react-query";
import type {
  Bundle,
  CapabilityStatement,
  CapabilityStatementRestResource,
  FhirResource,
  OperationOutcome,
  Parameters,
} from "fhir/r4";
import { useMemo } from "react";
import {
  APP_REQUEST_HEADER,
  getStoredServerHeaders,
  getStoredServerUrl,
  toRequestUrl,
} from "@/lib/fhir-config";
import { isOperationOutcome } from "@/lib/fhir-types";

export interface FhirError extends Error {
  status?: number;
  operationOutcome?: OperationOutcome;
}

declare module "@tanstack/react-query" {
  interface Register {
    defaultError: FhirError;
  }
}

/**
 * Retries a failed query once. A 4xx answer does not change on retry, so it
 * fails at once, except 408 (timeout) and 429 (rate limit).
 */
export function retryOnceUnlessClientError(
  failureCount: number,
  error: FhirError,
): boolean {
  const status = error.status ?? 0;
  if (status >= 400 && status < 500 && status !== 408 && status !== 429) {
    return false;
  }
  return failureCount < 1;
}

/** Request headers for `url`, with the active server's custom headers. */
export function fhirRequestHeaders(url: string): Headers {
  const headers = new Headers({ Accept: "application/fhir+json" });
  // A custom header to another origin needs a CORS preflight, which that server can refuse
  const { origin } = window.location;
  if (new URL(toRequestUrl(url), origin).origin === origin) {
    headers.set(APP_REQUEST_HEADER, "1");
  }
  // A page link can point at another host, which must not see the headers
  const serverUrl = getStoredServerUrl();
  // A page link is absolute, also when the server URL is a path such as "/fhir"
  const target = new URL(url, origin).href;
  const base = new URL(serverUrl, origin).href.replace(/\/+$/, "");
  if (target.startsWith(base) && "/?#".includes(target.charAt(base.length))) {
    for (const header of getStoredServerHeaders(serverUrl)) {
      headers.set(header.name, header.value);
    }
  }
  return headers;
}

export async function fhirFetch<T>(url: string): Promise<T> {
  const requestUrl = toRequestUrl(url);
  const response = await fetch(requestUrl, {
    headers: fhirRequestHeaders(url),
  });

  if (!response.ok) {
    const error: FhirError = new Error(
      `FHIR request failed: ${response.status} ${response.statusText}`,
    );
    error.status = response.status;
    const contentType = response.headers.get("Content-Type") ?? "";
    // The proxy gives the reason for its own errors as plain text
    if (
      requestUrl !== url &&
      contentType.toLowerCase().startsWith("text/plain")
    ) {
      const reason = await response.text().catch(() => "");
      error.message = reason.trim() || error.message;
      throw error;
    }
    try {
      const body = await response.json();
      if (isOperationOutcome(body)) {
        error.operationOutcome = body;
        error.message =
          body.issue[0]?.diagnostics ||
          body.issue[0]?.details?.text ||
          error.message;
      }
    } catch {
      // Ignore JSON parse errors
    }
    throw error;
  }

  return response.json();
}

interface MetadataResult {
  capability: CapabilityStatement;
  latency: number;
}

/** One metadata request serves the capability statement and the status check. */
function metadataQueryOptions(serverUrl: string) {
  return queryOptions({
    queryKey: ["fhir", "metadata", serverUrl],
    queryFn: async (): Promise<MetadataResult> => {
      const start = Date.now();
      const capability = await fhirFetch<CapabilityStatement>(
        `${serverUrl}/metadata`,
      );
      return { capability, latency: Date.now() - start };
    },
    enabled: !!serverUrl,
  });
}

export function useCapabilityStatement(serverUrl: string) {
  return useQuery({
    ...metadataQueryOptions(serverUrl),
    select: (result) => result.capability,
    staleTime: 5 * 60 * 1000, // 5 minutes
  });
}

export function getResourceTypes(
  capability: CapabilityStatement | undefined,
): CapabilityStatementRestResource["type"][] {
  if (!capability?.rest) {
    return [];
  }

  const serverRest = capability.rest.find((r) => r.mode === "server");
  if (!serverRest?.resource) {
    return [];
  }

  return serverRest.resource
    .map((r) => r.type)
    .filter((type) => !!type)
    .sort();
}

export function useServerStatus(serverUrl: string) {
  const query = useQuery({
    ...metadataQueryOptions(serverUrl),
    staleTime: 30 * 1000, // 30 seconds
  });

  return {
    ...query,
    isConnected: query.isSuccess,
    isDisconnected: query.isError,
    latency: query.data?.latency,
  };
}

export function useResourceCounts(serverUrl: string, enabled: boolean) {
  return useQuery({
    queryKey: ["fhir", "resource-counts", serverUrl],
    queryFn: async () => {
      const counts: Record<string, number> = {};

      const params = await fhirFetch<Parameters>(
        `${serverUrl}/$get-resource-counts`,
      );

      for (const param of params.parameter || []) {
        if (param.name && param.valueInteger !== undefined) {
          counts[param.name] = param.valueInteger;
        }
      }
      return counts;
    },
    staleTime: 60 * 1000,
    retry: 0,
    enabled: !!serverUrl && enabled,
  });
}

export function getPaginationLinks(bundle: Bundle | undefined) {
  if (!bundle?.link) {
    return {
      self: undefined,
      previous: undefined,
      next: undefined,
    };
  }

  const findLink = (relation: string) =>
    bundle.link?.find((l) => l.relation === relation)?.url;

  return {
    self: findLink("self"),
    previous: findLink("previous") || findLink("prev"),
    next: findLink("next"),
  };
}

export function useResourceSearchWithParams(
  serverUrl: string,
  resourceType: string,
  searchParams: Record<string, string>,
  pageUrl?: string,
  count = 50,
) {
  const url = useMemo(() => {
    if (pageUrl) return pageUrl;

    const params = new URLSearchParams();
    params.set("_count", count.toString());
    params.set("_total", "accurate");

    const sortedEntries = Object.entries(searchParams).sort(([a], [b]) =>
      a.localeCompare(b),
    );
    for (const [key, value] of sortedEntries) {
      if (value) {
        params.set(key, value);
      }
    }

    return `${serverUrl}/${resourceType}?${params.toString()}`;
  }, [serverUrl, resourceType, searchParams, pageUrl, count]);

  const searchParamsKey = useMemo(
    () => JSON.stringify(Object.entries(searchParams).sort()),
    [searchParams],
  );

  return useQuery({
    queryKey: ["fhir", "search", serverUrl, resourceType, searchParamsKey, url],
    queryFn: () => fhirFetch<Bundle<FhirResource>>(url),
    staleTime: 30 * 1000, // 30 seconds
    enabled: !!serverUrl && !!resourceType,
  });
}
