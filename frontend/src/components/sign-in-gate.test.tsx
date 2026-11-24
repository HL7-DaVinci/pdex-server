import {
  QueryClient,
  QueryClientProvider,
  useQuery,
} from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fhirFetch } from "@/hooks/use-fhir-api";
import { DEFAULT_SIGN_IN_SCOPE } from "@/lib/sign-in";
import { SignedInUser, SignInGate } from "./sign-in-gate";

vi.mock("@tanstack/react-router", () => ({ useNavigate: () => vi.fn() }));

// The server list of the app is read when fhir-config loads, so proxy mode
// must be on before the imports run
vi.hoisted(() => {
  window.APP_CONFIG = { proxy: { passthrough: false }, login: {} };
});

const content = "Patient search";
const offered = {
  offered: true,
  authorizationEndpoint: "https://auth.example.org/authorize",
  tokenEndpoint: "https://auth.example.org/token",
  capabilities: [],
  signedIn: false,
};

let status: object;
let countAnswer: () => Response;

/** A FHIR request of the content, which the server answers with 401. */
function Content() {
  useQuery({
    queryKey: ["fhir", "Condition"],
    queryFn: () => fhirFetch(`${window.location.origin}/fhir/Condition`),
  });
  return <p>{content}</p>;
}

function renderWithQueries(children: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>,
  );
}

function renderGate(onOpenSettings = vi.fn()) {
  renderWithQueries(
    <SignInGate onOpenSettings={onOpenSettings}>
      <p>{content}</p>
    </SignInGate>,
  );
}

describe("SignInGate", () => {
  beforeEach(() => {
    countAnswer = () => new Response(null, { status: 401 });
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        if (url.startsWith("/auth/status")) {
          return new Response(JSON.stringify(status));
        }
        if (url.includes("_summary=count")) {
          return countAnswer();
        }
        return new Response(null, { status: 401 });
      }),
    );
  });

  it("shows the endpoints, the client id, and Sign in when the client values are saved", async () => {
    status = {
      ...offered,
      client: {
        method: "client",
        clientId: "app-1",
        scope: "openid patient/*.rs",
      },
    };
    renderGate();

    expect(
      await screen.findByRole("button", { name: "Sign in" }),
    ).toBeInTheDocument();
    expect(screen.getByText(offered.authorizationEndpoint)).toBeInTheDocument();
    expect(screen.getByText(offered.tokenEndpoint)).toBeInTheDocument();
    expect(screen.getByText("app-1")).toBeInTheDocument();
    expect(screen.queryByText(content)).toBeNull();
  });

  it("opens the settings when no client values are saved", async () => {
    status = offered;
    const onOpenSettings = vi.fn();
    renderGate(onOpenSettings);

    fireEvent.click(
      await screen.findByRole("button", { name: "Open settings" }),
    );
    expect(onOpenSettings).toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Sign in" })).toBeNull();
  });

  it("signs in with the UDAP certificate in one step when the server offers UDAP and no values are saved", async () => {
    const authorizeUrl = "https://as.example/udap/authorize?state=abc";
    const udap = {
      offered: true,
      authorizationEndpoint: "https://as.example/udap/authorize",
      tokenEndpoint: "https://as.example/udap/token",
      registrationEndpoint: "https://as.example/udap/register",
      clientUri: "https://app.example/fhir",
    };
    status = { offered: false, signedIn: false, udap };
    const fetchMock = vi.fn<
      (url: string, init?: RequestInit) => Promise<Response>
    >(async (url) => {
      if (url.startsWith("/auth/status")) {
        return new Response(JSON.stringify(status));
      }
      if (url === "/auth/client") {
        return new Response(null, { status: 204 });
      }
      if (url === "/auth/login") {
        return new Response(JSON.stringify({ authorizeUrl }));
      }
      return new Response(null, { status: 401 });
    });
    vi.stubGlobal("fetch", fetchMock);
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });
    renderGate();

    const signIn = await screen.findByRole("button", { name: "Sign in" });
    expect(screen.getAllByRole("button", { name: "Sign in" })).toHaveLength(1);
    expect(screen.getByText(udap.authorizationEndpoint)).toBeInTheDocument();
    expect(screen.getByText(udap.tokenEndpoint)).toBeInTheDocument();
    expect(screen.getByText(udap.registrationEndpoint)).toBeInTheDocument();
    fireEvent.click(signIn);
    await waitFor(() => expect(assign).toHaveBeenCalledWith(authorizeUrl));
    const auth = fetchMock.mock.calls.filter(
      ([url]) => url === "/auth/client" || url === "/auth/login",
    );
    expect(auth.map(([url]) => url)).toEqual(["/auth/client", "/auth/login"]);
    expect(JSON.parse(String(auth[0][1]?.body))).toEqual({
      server: "/fhir",
      method: "udap",
      scope: DEFAULT_SIGN_IN_SCOPE,
    });
    expect(JSON.parse(String(auth[1][1]?.body))).toEqual({
      server: "/fhir",
      authorizationEndpoint: udap.authorizationEndpoint,
      tokenEndpoint: udap.tokenEndpoint,
      registrationEndpoint: udap.registrationEndpoint,
    });
    vi.unstubAllGlobals();
  });

  it("says that the app found no sign-in, with the plain http reason and the reason of a failed sign-in", async () => {
    const reason = "The server URL uses plain http.";
    status = {
      offered: false,
      plainHttp: true,
      reason,
      signedIn: false,
      error: "The sign-in failed",
    };
    renderGate();

    expect(
      await screen.findByText("The app found no sign-in for this server."),
    ).toBeInTheDocument();
    expect(screen.getByText(reason)).toBeInTheDocument();
    expect(screen.getByText("The sign-in failed")).toBeInTheDocument();
    expect(screen.queryByText(content)).toBeNull();
  });

  it("shows the content and the reason of a failed sign-in when the count request does not answer 401", async () => {
    status = { ...offered, error: "The sign-in failed" };
    countAnswer = () =>
      new Response(JSON.stringify({ resourceType: "Bundle", total: 0 }));
    renderGate();

    expect(await screen.findByText("The sign-in failed")).toBeInTheDocument();
    expect(screen.getByText(content)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Sign in" })).toBeNull();
  });

  it("shows the page when a later FHIR request answers 401", async () => {
    status = offered;
    let countCalls = 0;
    countAnswer = () =>
      countCalls++ === 0
        ? new Response(JSON.stringify({ resourceType: "Bundle", total: 0 }))
        : new Response(null, { status: 401 });
    renderWithQueries(
      <SignInGate onOpenSettings={vi.fn()}>
        <Content />
      </SignInGate>,
    );

    await screen.findByText(content);
    expect(
      await screen.findByRole("button", { name: "Open settings" }),
    ).toBeInTheDocument();
    expect(countCalls).toBe(2);
  });
});

describe("SignedInUser", () => {
  it("shows who is signed in and Sign out", async () => {
    const fhirUser = `${window.location.origin}/fhir/Patient/1002`;
    status = { ...offered, signedIn: true, fhirUser };
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify(status))),
    );
    renderWithQueries(<SignedInUser />);

    const label = await screen.findByText("Signed in as Patient/1002");
    expect(label).toHaveAttribute("title", fhirUser);
    expect(
      screen.getByRole("button", { name: "Sign out" }),
    ).toBeInTheDocument();
  });

  it("shows the type and the id of a fhirUser with a version", async () => {
    const fhirUser = `${window.location.origin}/fhir/Practitioner/123/_history/4`;
    status = { ...offered, signedIn: true, fhirUser };
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify(status))),
    );
    renderWithQueries(<SignedInUser />);

    const label = await screen.findByText("Signed in as Practitioner/123");
    expect(label).toHaveAttribute("title", fhirUser);
  });
});
