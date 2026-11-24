import {
  type QueryClient,
  skipToken,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Separator } from "@/components/ui/separator";
import { toAuthServer } from "@/lib/fhir-config";
import {
  getSignInStatus,
  getStoredSignInFields,
  isSignInAvailable,
  type SignInMethod,
  type SignInRequest,
  type SignInStatus,
  saveClient,
  setStoredSignInFields,
  signOut,
  startSignIn,
} from "@/lib/sign-in";

// setTimeout runs at once for a delay above 2^31 - 1 milliseconds
const MAX_TIMER_DELAY = 2 ** 31 - 1;

export const INSECURE_PAGE =
  "The sign-in needs https, and plain http works only on localhost.";

function statusKey(server: string | undefined) {
  return ["auth", "status", server];
}

/** Reads the server data and the sign-in status again. */
function reload(queryClient: QueryClient, server: string) {
  queryClient.invalidateQueries({ queryKey: ["fhir"] });
  queryClient.invalidateQueries({ queryKey: statusKey(server) });
}

// Compared with false, so an environment with no isSecureContext keeps the
// form. The server check is the authority.
export function isInsecurePage() {
  return window.isSecureContext === false;
}

/**
 * The sign-in status of the server at `serverUrl`. `server` is the value of
 * the `/auth` requests, and it is undefined when this app has no sign-in for
 * that server.
 */
export function useSignInStatus(serverUrl: string) {
  const server = isSignInAvailable() ? toAuthServer(serverUrl) : undefined;
  const query = useQuery({
    queryKey: statusKey(server),
    queryFn: server === undefined ? skipToken : () => getSignInStatus(server),
    // The status can change while the dialog is closed
    staleTime: 0,
    // The server sends the error one time, and a read on window focus would hide it
    refetchOnWindowFocus: false,
  });
  return { server, query };
}

/** Reloads the server data and the sign-in status when the token set expires. */
export function useReloadAtExpiry(
  server: string | undefined,
  expiresAt: string | undefined,
) {
  const queryClient = useQueryClient();
  useEffect(() => {
    if (server === undefined || !expiresAt) {
      return;
    }
    const delay = Date.parse(expiresAt) - Date.now();
    const timer = setTimeout(
      () => reload(queryClient, server),
      Math.min(delay, MAX_TIMER_DELAY),
    );
    return () => clearTimeout(timer);
  }, [expiresAt, queryClient, server]);
}

export function Value({ label, value }: { label: string; value: string }) {
  return (
    <div className="text-xs text-muted-foreground">
      <span className="font-medium">{label}:</span>{" "}
      <code className="bg-muted px-1 py-0.5 rounded text-xs break-all">
        {value}
      </code>
    </div>
  );
}

interface SignInPartProps {
  server: string;
  status: SignInStatus;
}

/**
 * Starts the sign-in with the client values that the session holds for
 * `server`. With `udapScope`, the button first saves the UDAP values with that
 * scope. The sign-in call gets the members of `person`. A page that is not a
 * secure context shows a sentence in place of the button.
 */
export function SignInButton({
  server,
  status,
  udapScope,
  person,
  disabled,
}: SignInPartProps & {
  udapScope?: string;
  person?: Pick<SignInRequest, "loginHint" | "skipForm" | "scope">;
  disabled?: boolean;
}) {
  const queryClient = useQueryClient();
  const signIn = useMutation({
    mutationFn: async () => {
      if (udapScope !== undefined) {
        await saveClient({ server, method: "udap", scope: udapScope });
      }
      const udap = udapScope !== undefined || status.client?.method === "udap";
      const endpoints = udap ? status.udap : status;
      return startSignIn({
        server,
        authorizationEndpoint: endpoints?.authorizationEndpoint ?? "",
        tokenEndpoint: endpoints?.tokenEndpoint ?? "",
        ...(udap && {
          registrationEndpoint: status.udap?.registrationEndpoint ?? "",
        }),
        ...person,
      });
    },
    onSuccess: (authorizeUrl) => window.location.assign(authorizeUrl),
    // The status can differ after a failed start, for example other endpoints
    // after a 409 or no client values after a restart of the server
    onError: () =>
      queryClient.invalidateQueries({ queryKey: statusKey(server) }),
  });

  if (isInsecurePage()) {
    return <p className="text-xs text-destructive">{INSECURE_PAGE}</p>;
  }
  return (
    <>
      {signIn.error && (
        <p className="text-xs text-destructive">{signIn.error.message}</p>
      )}
      <Button
        size="sm"
        onClick={() => signIn.mutate()}
        disabled={disabled || signIn.isPending || signIn.isSuccess}
      >
        Sign in
      </Button>
    </>
  );
}

/**
 * Signs out at `server`, reloads the server data and the status, then goes to
 * the start route.
 */
export function SignOutButton({ server }: { server: string }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const signOutMutation = useMutation({
    mutationFn: () => signOut(server),
    onSuccess: () => {
      reload(queryClient, server);
      navigate({ to: "/" });
    },
  });

  return (
    <>
      {signOutMutation.error && (
        <p className="text-xs text-destructive">
          {signOutMutation.error.message}
        </p>
      )}
      <Button
        variant="outline"
        size="sm"
        onClick={() => signOutMutation.mutate()}
        disabled={signOutMutation.isPending}
      >
        Sign out
      </Button>
    </>
  );
}

/**
 * The select of the method shows when the server offers both sign-ins. Its
 * start value is the stored method, else UDAP.
 */
function SignInForm({ server, status }: SignInPartProps) {
  const [method, setMethod] = useState<SignInMethod>(
    () => getStoredSignInFields(server).method ?? "udap",
  );
  const both = status.offered && status.udap?.offered;
  const shown = both ? method : status.udap?.offered ? "udap" : "client";

  return (
    <>
      {both && (
        <div className="space-y-1">
          <Label htmlFor="sign-in-method">Sign in with</Label>
          <Select
            value={method}
            onValueChange={(value) => setMethod(value as SignInMethod)}
          >
            <SelectTrigger id="sign-in-method" size="sm" className="text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="udap">
                Certificate of this app (UDAP)
              </SelectItem>
              <SelectItem value="client">Client id and secret</SelectItem>
            </SelectContent>
          </Select>
        </div>
      )}
      {shown === "udap" ? (
        <UdapForm server={server} status={status} />
      ) : (
        <ClientForm server={server} status={status} />
      )}
    </>
  );
}

/** The UDAP sign-in needs no client id and no secret, only the scope. */
function UdapForm({ server, status }: SignInPartProps) {
  const queryClient = useQueryClient();
  const [scope, setScope] = useState(() => getStoredSignInFields(server).scope);
  const save = useMutation({
    mutationFn: () => saveClient({ server, method: "udap", scope }),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: statusKey(server) }),
  });
  const handleSave = () => {
    const { clientId } = getStoredSignInFields(server);
    setStoredSignInFields(server, { clientId, scope, method: "udap" });
    save.mutate();
  };

  return (
    <>
      <Value
        label="Authorization endpoint"
        value={status.udap?.authorizationEndpoint ?? ""}
      />
      <Value label="Token endpoint" value={status.udap?.tokenEndpoint ?? ""} />
      <Value
        label="Registration endpoint"
        value={status.udap?.registrationEndpoint ?? ""}
      />
      <Value label="Client URI" value={status.udap?.clientUri ?? ""} />
      <Value
        label="Callback URL"
        value={`${window.location.origin}/auth/callback`}
      />
      <div className="space-y-1">
        <Label htmlFor="sign-in-scope">Scopes</Label>
        <Input
          id="sign-in-scope"
          value={scope}
          onChange={(e) => setScope(e.target.value)}
          className="h-8 text-xs"
        />
      </div>
      {save.error && (
        <p className="text-xs text-destructive">{save.error.message}</p>
      )}
      <Button
        variant="outline"
        size="sm"
        onClick={handleSave}
        disabled={save.isPending}
      >
        Save
      </Button>
      {status.client?.method === "udap" && (
        <>
          <Value label="Saved scopes" value={status.client.scope} />
          <SignInButton server={server} status={status} />
        </>
      )}
    </>
  );
}

function ClientForm({ server, status }: SignInPartProps) {
  const queryClient = useQueryClient();
  const [fields, setFields] = useState(() => {
    const { clientId, scope } = getStoredSignInFields(server);
    return { clientId, scope };
  });
  // The secret stays in this state until the save. This app never stores it.
  const [clientSecret, setClientSecret] = useState("");
  const save = useMutation({
    mutationFn: () =>
      saveClient({ server, method: "client", ...fields, clientSecret }),
    onSuccess: () => {
      setClientSecret("");
      queryClient.invalidateQueries({ queryKey: statusKey(server) });
    },
  });
  const handleSave = () => {
    setStoredSignInFields(server, { ...fields, method: "client" });
    save.mutate();
  };
  const saved = status.client?.method === "client" ? status.client : undefined;

  return (
    <>
      <Value
        label="Authorization endpoint"
        value={status.authorizationEndpoint ?? ""}
      />
      <Value label="Token endpoint" value={status.tokenEndpoint ?? ""} />
      <div className="space-y-1">
        <Label htmlFor="sign-in-client-id">Client id</Label>
        <Input
          id="sign-in-client-id"
          autoComplete="off"
          value={fields.clientId}
          onChange={(e) => setFields({ ...fields, clientId: e.target.value })}
          className="h-8 text-xs"
        />
      </div>
      <div className="space-y-1">
        <Label htmlFor="sign-in-client-secret">Client secret</Label>
        <Input
          id="sign-in-client-secret"
          type="password"
          autoComplete="new-password"
          placeholder={
            saved?.hasSecret && fields.clientId === saved.clientId
              ? "Empty keeps the saved secret"
              : "Empty for a public client"
          }
          value={clientSecret}
          onChange={(e) => setClientSecret(e.target.value)}
          className="h-8 text-xs"
        />
      </div>
      <div className="space-y-1">
        <Label htmlFor="sign-in-scope">Scopes</Label>
        <Input
          id="sign-in-scope"
          value={fields.scope}
          onChange={(e) => setFields({ ...fields, scope: e.target.value })}
          className="h-8 text-xs"
        />
      </div>
      <Value
        label="Callback URL"
        value={`${window.location.origin}/auth/callback`}
      />
      <p className="text-xs text-muted-foreground">
        Register this URL at the authorization server.
      </p>
      {save.error && (
        <p className="text-xs text-destructive">{save.error.message}</p>
      )}
      <Button
        variant="outline"
        size="sm"
        onClick={handleSave}
        disabled={save.isPending}
      >
        Save
      </Button>
      {saved && (
        <>
          <Value label="Saved client id" value={saved.clientId} />
          <Value
            label="Saved client secret"
            value={saved.hasSecret ? "yes" : "none"}
          />
          <Value label="Saved scopes" value={saved.scope} />
          <SignInButton server={server} status={status} />
        </>
      )}
    </>
  );
}

function SignedIn({ server, status }: SignInPartProps) {
  useReloadAtExpiry(server, status.expiresAt);

  return (
    <>
      {status.fhirUser && (
        <Value label="Signed in as" value={status.fhirUser} />
      )}
      {status.patient && <Value label="Patient" value={status.patient} />}
      {status.scope && <Value label="Scopes granted" value={status.scope} />}
      {status.expiresAt && (
        <Value
          label="Expires"
          value={new Date(status.expiresAt).toLocaleString()}
        />
      )}
      {status.patientMissing && (
        <p className="text-xs text-warning">
          The authorization server granted a patient scope but sent no patient.
        </p>
      )}
      <SignOutButton server={server} />
    </>
  );
}

/** Sign-in at the authorization server of the selected FHIR server. */
export function SignInSection({ serverUrl }: { serverUrl: string }) {
  const {
    server,
    query: { data: status },
  } = useSignInStatus(serverUrl);

  // A signed-in tester can sign out also when the server does not offer sign-in
  if (
    server === undefined ||
    !status ||
    !(
      status.udap ||
      status.offered ||
      status.signedIn ||
      status.error ||
      status.plainHttp
    )
  ) {
    return null;
  }
  return (
    <>
      <Separator />
      <div className="space-y-2">
        <Label>Sign in</Label>
        {status.signedIn ? (
          <SignedIn server={server} status={status} />
        ) : (
          (status.offered || status.udap?.offered) &&
          (isInsecurePage() ? (
            <p className="text-xs text-destructive">{INSECURE_PAGE}</p>
          ) : (
            <SignInForm server={server} status={status} />
          ))
        )}
        {status.plainHttp && (
          <p className="text-xs text-destructive">{status.reason}</p>
        )}
        {status.udap?.offered === false && (
          <p className="text-xs text-destructive">{status.udap.reason}</p>
        )}
        {status.error && (
          <p className="text-xs text-destructive">{status.error}</p>
        )}
      </div>
    </>
  );
}
