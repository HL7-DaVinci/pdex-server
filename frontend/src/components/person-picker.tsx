import { useQuery } from "@tanstack/react-query";
import { type ReactNode, useState } from "react";
import { SignInButton } from "@/components/sign-in-section";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { getPeople, type SignInStatus } from "@/lib/sign-in";

export const PRACTITIONER_SCOPE = "openid fhirUser user/*.cruds";
export const PATIENT_SCOPE = "openid fhirUser launch/patient patient/*.cruds";

const TABS = {
  practitioners: {
    label: "Practitioners",
    resourceType: "Practitioner",
    scope: PRACTITIONER_SCOPE,
    placeholder: "Select a practitioner...",
    none: "This server has no Practitioner resources.",
  },
  patients: {
    label: "Patients",
    resourceType: "Patient",
    scope: PATIENT_SCOPE,
    placeholder: "Select a patient...",
    none: "This server has no Patient resources.",
  },
} as const;

type Tab = keyof typeof TABS;

const SKIP_FORM_STORAGE_KEY = "fhir-sign-in-skip-form";

// The browser can refuse access to its storage. The checkbox then starts
// checked.
function readSkipForm(): boolean {
  try {
    return localStorage.getItem(SKIP_FORM_STORAGE_KEY) !== "false";
  } catch {
    return true;
  }
}

function writeSkipForm(skipForm: boolean) {
  try {
    localStorage.setItem(SKIP_FORM_STORAGE_KEY, String(skipForm));
  } catch {
    // The checkbox keeps its value only while the page is open
  }
}

interface PersonPickerProps {
  server: string;
  status: SignInStatus;
  /** Goes to `SignInButton` as the gate computes it. */
  udapScope?: string;
  /** The sign-in with no person. */
  children: ReactNode;
}

/**
 * Signs in as a Practitioner or a Patient of the server, with the scope of
 * the tab. A failed read of the list shows `children` in place of the picker.
 */
export function PersonPicker({
  server,
  status,
  udapScope,
  children,
}: PersonPickerProps) {
  const people = useQuery({ queryKey: ["auth", "people"], queryFn: getPeople });
  const [withoutPerson, setWithoutPerson] = useState(false);
  const [tab, setTab] = useState<Tab>("practitioners");
  const [id, setId] = useState("");
  const [skipForm, setSkipForm] = useState(readSkipForm);

  if (people.isError) {
    return (
      <>
        <p className="text-sm text-destructive">
          The app could not read the people of this server.
        </p>
        {children}
      </>
    );
  }
  if (withoutPerson) {
    return (
      <>
        {children}
        <div>
          <Button
            variant="link"
            size="sm"
            className="px-0"
            onClick={() => setWithoutPerson(false)}
          >
            Sign in as a person
          </Button>
        </div>
      </>
    );
  }

  const { resourceType, scope, placeholder, none } = TABS[tab];
  const list = people.data?.[tab];
  return (
    <>
      <Tabs
        value={tab}
        onValueChange={(value) => {
          setTab(value as Tab);
          setId("");
        }}
      >
        <TabsList>
          <TabsTrigger value="practitioners">
            {TABS.practitioners.label}
          </TabsTrigger>
          <TabsTrigger value="patients">{TABS.patients.label}</TabsTrigger>
        </TabsList>
        <TabsContent value={tab} className="space-y-1">
          {list?.length === 0 ? (
            <p className="text-sm">{none}</p>
          ) : (
            <>
              <Label htmlFor="sign-in-person">Person</Label>
              <Select value={id} onValueChange={setId} disabled={!list}>
                <SelectTrigger
                  id="sign-in-person"
                  size="sm"
                  className="w-full text-xs"
                >
                  <SelectValue placeholder={placeholder} />
                </SelectTrigger>
                <SelectContent>
                  {list?.map((person) => (
                    <SelectItem key={person.id} value={person.id}>
                      {person.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </>
          )}
        </TabsContent>
      </Tabs>
      <div className="flex items-center gap-2">
        <Checkbox
          id="sign-in-skip-form"
          checked={skipForm}
          onCheckedChange={(checked) => {
            setSkipForm(checked === true);
            writeSkipForm(checked === true);
          }}
        />
        <Label htmlFor="sign-in-skip-form">
          Skip the sign-in form of the authorization server
        </Label>
      </div>
      <SignInButton
        server={server}
        status={status}
        udapScope={udapScope}
        person={{ loginHint: `${resourceType}/${id}`, skipForm, scope }}
        disabled={!id}
      />
      <div>
        <Button
          variant="link"
          size="sm"
          className="px-0"
          onClick={() => setWithoutPerson(true)}
        >
          Sign in without a person
        </Button>
      </div>
    </>
  );
}
