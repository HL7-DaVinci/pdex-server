import { Search, X } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

interface PatientSearchProps {
  onSearch: (params: {
    family?: string;
    given?: string;
    birthdate?: string;
    identifier?: string;
  }) => void;
}

export function PatientSearch({ onSearch }: PatientSearchProps) {
  const [family, setFamily] = useState("");
  const [given, setGiven] = useState("");
  const [birthdate, setBirthdate] = useState("");
  const [identifier, setIdentifier] = useState("");

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSearch({
      family: family || undefined,
      given: given || undefined,
      birthdate: birthdate || undefined,
      identifier: identifier || undefined,
    });
  };

  const handleClear = () => {
    setFamily("");
    setGiven("");
    setBirthdate("");
    setIdentifier("");
    onSearch({});
  };

  const hasValues = !!(family || given || birthdate || identifier);

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
        <div className="space-y-1.5">
          <Label
            htmlFor="family"
            className="text-xs font-medium text-muted-foreground"
          >
            Last Name
          </Label>
          <Input
            id="family"
            placeholder="Smith"
            value={family}
            onChange={(e) => setFamily(e.target.value)}
            className="h-9"
          />
        </div>
        <div className="space-y-1.5">
          <Label
            htmlFor="given"
            className="text-xs font-medium text-muted-foreground"
          >
            First Name
          </Label>
          <Input
            id="given"
            placeholder="John"
            value={given}
            onChange={(e) => setGiven(e.target.value)}
            className="h-9"
          />
        </div>
        <div className="space-y-1.5">
          <Label
            htmlFor="birthdate"
            className="text-xs font-medium text-muted-foreground"
          >
            Date of Birth
          </Label>
          <Input
            id="birthdate"
            type="date"
            value={birthdate}
            onChange={(e) => setBirthdate(e.target.value)}
            className="h-9"
          />
        </div>
        <div className="space-y-1.5">
          <Label
            htmlFor="identifier"
            className="text-xs font-medium text-muted-foreground"
          >
            MRN / Identifier
          </Label>
          <Input
            id="identifier"
            placeholder="12345"
            value={identifier}
            onChange={(e) => setIdentifier(e.target.value)}
            className="h-9"
          />
        </div>
      </div>
      <div className="flex gap-2">
        <Button
          type="submit"
          size="sm"
          className="gap-2 active:scale-[0.98] active:translate-y-px transition-transform"
        >
          <Search className="h-4 w-4" />
          Search Patients
        </Button>
        {hasValues && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="gap-2"
            onClick={handleClear}
          >
            <X className="h-4 w-4" />
            Clear
          </Button>
        )}
      </div>
    </form>
  );
}
