import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DEFAULT_SIGN_IN_SCOPE, getStoredSignInFields } from "@/lib/sign-in";
import { SignInSection } from "./sign-in-section";

const navigate = vi.hoisted(() => vi.fn());
vi.mock("@tanstack/react-router", () => ({ useNavigate: () => navigate }));

const origin = window.location.origin;
const authorizeUrl = "https://auth.example.org/authorize?state=abc";
const offered = {
  offered: true,
  authorizationEndpoint: "https://auth.example.org/authorize",
  tokenEndpoint: "https://auth.example.org/token",
  capabilities: [],
  signedIn: false,
};

let status: object;
const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(
  async (url) => {
    if (url.startsWith("/auth/status")) {
      return new Response(JSON.stringify(status));
    }
    if (url === "/auth/login") {
      return new Response(JSON.stringify({ authorizeUrl }));
    }
    if (url === "/auth/client" || url === "/auth/logout") {
      return new Response(null, { status: 204 });
    }
    return new Response("{}");
  },
);

function requestTo(path: string): RequestInit {
  const call = fetchMock.mock.calls.find(([url]) => url.startsWith(path));
  expect(call).toBeDefined();
  return call?.[1] ?? {};
}

function renderSection(
  queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  }),
) {
  return render(
    <QueryClientProvider client={queryClient}>
      <SignInSection serverUrl={`${origin}/fhir`} />
    </QueryClientProvider>,
  );
}

describe("SignInSection", () => {
  beforeEach(() => {
    localStorage.clear();
    status = offered;
    fetchMock.mockClear();
    vi.stubGlobal("fetch", fetchMock);
    window.APP_CONFIG = { proxy: { passthrough: false }, login: {} };
  });

  afterEach(() => {
    delete window.APP_CONFIG;
    vi.unstubAllGlobals();
  });

  it("shows both endpoints and the callback URL when the server offers sign-in", async () => {
    renderSection();

    expect(
      await screen.findByText(offered.authorizationEndpoint),
    ).toBeInTheDocument();
    expect(screen.getByText(offered.tokenEndpoint)).toBeInTheDocument();
    expect(screen.getByText(`${origin}/auth/callback`)).toBeInTheDocument();
    const init = requestTo(
      `/auth/status?server=${encodeURIComponent("/fhir")}`,
    );
    expect(new Headers(init.headers).get("X-App-Request")).toBe("1");
  });

  it("shows nothing in thin mode and asks the server nothing", async () => {
    window.APP_CONFIG = { login: {} };
    const { container } = renderSection();
    await act(() => Promise.resolve());

    expect(container).toBeEmptyDOMElement();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("saves the client values, then signs in with them, and keeps the client id and the scope, never the secret", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });
    const secret = "s3cret+/=%:";
    renderSection();

    fireEvent.change(await screen.findByLabelText("Client id"), {
      target: { value: "app-1" },
    });
    fireEvent.change(screen.getByLabelText("Client secret"), {
      target: { value: secret },
    });
    expect(screen.getByLabelText("Scopes")).toHaveValue(DEFAULT_SIGN_IN_SCOPE);
    expect(screen.queryByRole("button", { name: "Sign in" })).toBeNull();
    status = {
      ...offered,
      client: {
        method: "client",
        clientId: "app-1",
        scope: DEFAULT_SIGN_IN_SCOPE,
        hasSecret: true,
      },
    };
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    fireEvent.click(await screen.findByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith(authorizeUrl));
    const save = requestTo("/auth/client");
    expect(save.method).toBe("POST");
    expect(new Headers(save.headers).get("X-App-Request")).toBe("1");
    expect(JSON.parse(String(save.body))).toEqual({
      server: "/fhir",
      method: "client",
      clientId: "app-1",
      clientSecret: secret,
      scope: DEFAULT_SIGN_IN_SCOPE,
    });
    const signIn = requestTo("/auth/login");
    expect(signIn.method).toBe("POST");
    expect(JSON.parse(String(signIn.body))).toEqual({
      server: "/fhir",
      authorizationEndpoint: offered.authorizationEndpoint,
      tokenEndpoint: offered.tokenEndpoint,
    });
    expect(screen.getByLabelText("Client secret")).toHaveValue("");
    expect(getStoredSignInFields("/fhir")).toEqual({
      clientId: "app-1",
      scope: DEFAULT_SIGN_IN_SCOPE,
      method: "client",
    });
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i) ?? "";
      expect(localStorage.getItem(key)).not.toContain(secret);
    }
  });

  it("shows the saved secret, and an empty field keeps it for the saved client id", async () => {
    status = {
      ...offered,
      client: {
        method: "client",
        clientId: "app-1",
        scope: DEFAULT_SIGN_IN_SCOPE,
        hasSecret: true,
      },
    };
    renderSection();

    const row = (await screen.findByText("Saved client secret:")).parentElement;
    expect(row).toHaveTextContent("Saved client secret: yes");
    const secret = screen.getByLabelText("Client secret");
    expect(secret).toHaveAttribute("placeholder", "Empty for a public client");
    fireEvent.change(screen.getByLabelText("Client id"), {
      target: { value: "app-1" },
    });
    expect(secret).toHaveAttribute(
      "placeholder",
      "Empty keeps the saved secret",
    );
  });

  it("offers the UDAP sign-in as the start value of the select, saves the method and the scope, and signs in with the three endpoints", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });
    const udap = {
      offered: true,
      authorizationEndpoint: "https://as.example/udap/authorize",
      tokenEndpoint: "https://as.example/udap/token",
      registrationEndpoint: "https://as.example/udap/register",
      clientUri: "https://app.example/fhir",
    };
    status = { ...offered, udap };
    renderSection();

    expect(await screen.findByLabelText("Sign in with")).toHaveTextContent(
      "Certificate of this app (UDAP)",
    );
    expect(screen.getByText(udap.registrationEndpoint)).toBeInTheDocument();
    expect(screen.getByText(udap.clientUri)).toBeInTheDocument();
    expect(screen.queryByLabelText("Client id")).toBeNull();
    status = {
      ...offered,
      udap,
      client: { method: "udap", scope: DEFAULT_SIGN_IN_SCOPE },
    };
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    fireEvent.click(await screen.findByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith(authorizeUrl));
    expect(JSON.parse(String(requestTo("/auth/client").body))).toEqual({
      server: "/fhir",
      method: "udap",
      scope: DEFAULT_SIGN_IN_SCOPE,
    });
    expect(JSON.parse(String(requestTo("/auth/login").body))).toEqual({
      server: "/fhir",
      authorizationEndpoint: udap.authorizationEndpoint,
      tokenEndpoint: udap.tokenEndpoint,
      registrationEndpoint: udap.registrationEndpoint,
    });
    expect(getStoredSignInFields("/fhir")).toEqual({
      clientId: "",
      scope: DEFAULT_SIGN_IN_SCOPE,
      method: "udap",
    });
  });

  it("shows the reason when the server refuses plain http", async () => {
    const reason = "The server URL uses plain http.";
    status = { offered: false, plainHttp: true, reason, signedIn: false };
    renderSection();

    expect(await screen.findByText(reason)).toBeInTheDocument();
  });

  it("shows no secret field on a page that is not a secure context", async () => {
    vi.stubGlobal("isSecureContext", false);
    renderSection();

    expect(await screen.findByText(/needs https/)).toBeInTheDocument();
    expect(screen.queryByLabelText("Client secret")).toBeNull();
  });

  it("shows Sign out when signed in at a server that does not offer sign-in", async () => {
    status = { offered: false, signedIn: true, scope: "openid patient/*.rs" };
    renderSection();

    expect(
      await screen.findByRole("button", { name: "Sign out" }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText("Client id")).toBeNull();
  });

  it("signs out and reads the server data again", async () => {
    const fhirUser = `${origin}/fhir/Patient/1002`;
    status = {
      ...offered,
      signedIn: true,
      scope: "openid fhirUser patient/*.rs",
      patient: "1002",
      fhirUser,
      expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    };
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    renderSection(queryClient);

    expect(await screen.findByText(fhirUser)).toBeInTheDocument();
    status = offered;
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

    expect(await screen.findByLabelText("Client id")).toBeInTheDocument();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["fhir"] });
    expect(navigate).toHaveBeenCalledWith({ to: "/" });
    const init = requestTo("/auth/logout");
    expect(init.method).toBe("POST");
    expect(new Headers(init.headers).get("X-App-Request")).toBe("1");
    expect(JSON.parse(String(init.body))).toEqual({ server: "/fhir" });
  });
});
