import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { DEFAULT_SIGN_IN_SCOPE, setStoredSignInFields } from "@/lib/sign-in";
import { SignInGate } from "./sign-in-gate";

// The server list of the app is read when fhir-config loads, so proxy mode
// must be on before the imports run
vi.hoisted(() => {
  window.APP_CONFIG = {
    proxy: { passthrough: false },
    login: { people: true },
  };
});

const authorizeUrl = "https://as.example/udap/authorize?state=abc";
const endpoints = {
  authorizationEndpoint: "https://as.example/udap/authorize",
  tokenEndpoint: "https://as.example/udap/token",
  registrationEndpoint: "https://as.example/udap/register",
};
const savedUdap = {
  offered: false,
  signedIn: false,
  udap: { offered: true, ...endpoints },
  client: { method: "udap", scope: DEFAULT_SIGN_IN_SCOPE },
};

let status: object;
const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>(
  async (url) => {
    if (url.startsWith("/auth/status")) {
      return new Response(JSON.stringify(status));
    }
    if (url === "/auth/people") {
      return new Response(
        JSON.stringify({
          practitioners: [{ id: "pr-1", name: "Jane Doe" }],
          patients: [{ id: "pa-1", name: "Sam Roe" }],
        }),
      );
    }
    if (url === "/auth/login") {
      return new Response(JSON.stringify({ authorizeUrl }));
    }
    if (url === "/auth/client") {
      return new Response(null, { status: 204 });
    }
    // The Patient count request
    return new Response(null, { status: 401 });
  },
);
const assign = vi.fn();

function renderGate() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <SignInGate onOpenSettings={vi.fn()}>
        <p>Patient search</p>
      </SignInGate>
    </QueryClientProvider>,
  );
}

/** The path and the body of each `/auth/client` and `/auth/login` call. */
function signInCalls() {
  return fetchMock.mock.calls
    .filter(([url]) => url === "/auth/client" || url === "/auth/login")
    .map(([url, init]) => [url, JSON.parse(String(init?.body))]);
}

async function chooseAndSignIn(name: string) {
  const person = await screen.findByRole("combobox", { name: "Person" });
  // The select is disabled until the list arrives
  await waitFor(() => expect(person).toBeEnabled());
  fireEvent.keyDown(person, { key: "Enter" });
  fireEvent.click(screen.getByRole("option", { name }));
  // The open select hides the rest of the page from role queries
  const signIn = await screen.findByRole("button", { name: "Sign in" });
  await waitFor(() => expect(signIn).toBeEnabled());
  fireEvent.click(signIn);
  await waitFor(() => expect(assign).toHaveBeenCalledWith(authorizeUrl));
}

describe("PersonPicker", () => {
  beforeEach(() => {
    localStorage.clear();
    // jsdom has no scrollIntoView, which the open select calls
    Element.prototype.scrollIntoView = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("location", { ...window.location, assign });
  });

  it("signs in as a practitioner and as a patient from the picker, and shows the plain sign-in without a person", async () => {
    status = savedUdap;
    const first = renderGate();
    await screen.findByRole("combobox", { name: "Person" });
    expect(screen.getByRole("tab", { name: "Practitioners" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(
      screen.getByRole("checkbox", {
        name: "Skip the sign-in form of the authorization server",
      }),
    ).toBeChecked();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeDisabled();
    await chooseAndSignIn("Jane Doe");
    expect(signInCalls()).toEqual([
      [
        "/auth/login",
        {
          server: "/fhir",
          ...endpoints,
          loginHint: "Practitioner/pr-1",
          skipForm: true,
          scope: "openid fhirUser user/*.cruds",
        },
      ],
    ]);
    first.unmount();

    // With no saved values, the save takes the stored scope and only the
    // sign-in call has the scope of the tab. The stored scope differs from
    // both tab scopes, so the two calls show which scope each one got.
    fetchMock.mockClear();
    assign.mockClear();
    const storedScope = "openid fhirUser launch/patient patient/*.cruds";
    setStoredSignInFields("/fhir", {
      clientId: "",
      scope: storedScope,
      method: "udap",
    });
    status = { offered: false, signedIn: false, udap: savedUdap.udap };
    const second = renderGate();
    fireEvent.mouseDown(await screen.findByRole("tab", { name: "Patients" }));
    await chooseAndSignIn("Sam Roe");
    expect(signInCalls()).toEqual([
      ["/auth/client", { server: "/fhir", method: "udap", scope: storedScope }],
      [
        "/auth/login",
        {
          server: "/fhir",
          ...endpoints,
          loginHint: "Patient/pa-1",
          skipForm: true,
          scope: "openid fhirUser launch/patient patient/*.cruds",
        },
      ],
    ]);
    second.unmount();

    status = savedUdap;
    renderGate();
    fireEvent.click(
      await screen.findByRole("button", { name: "Sign in without a person" }),
    );
    expect(screen.queryByRole("combobox")).toBeNull();
    expect(
      screen.getByText(endpoints.authorizationEndpoint),
    ).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Sign in" })).toHaveLength(1);
  });
});
