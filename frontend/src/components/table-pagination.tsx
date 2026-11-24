import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

interface TablePaginationProps {
  shown: number;
  total?: number;
  pageSize: number;
  onPageSizeChange: (pageSize: number) => void;
  previous?: string;
  next?: string;
  onPageChange: (url: string) => void;
  disabled?: boolean;
}

export function TablePagination({
  shown,
  total,
  pageSize,
  onPageSizeChange,
  previous,
  next,
  onPageChange,
  disabled = false,
}: TablePaginationProps) {
  return (
    <div className="flex items-center justify-between px-4 py-3">
      <div className="flex items-center gap-4">
        <span className="text-sm text-muted-foreground">
          {total !== undefined && (
            <>
              Showing {shown} of {total.toLocaleString()}
            </>
          )}
        </span>
        <div className="flex items-center gap-2">
          <label
            htmlFor="page-size-select"
            className="text-sm text-muted-foreground"
          >
            Per page:
          </label>
          <Select
            value={pageSize.toString()}
            onValueChange={(v) => onPageSizeChange(Number(v))}
          >
            <SelectTrigger id="page-size-select" className="w-20 h-8">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="20">20</SelectItem>
              <SelectItem value="50">50</SelectItem>
              <SelectItem value="100">100</SelectItem>
              <SelectItem value="200">200</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          onClick={() => previous && onPageChange(previous)}
          disabled={!previous || disabled}
        >
          <ChevronLeft className="h-4 w-4 mr-1" />
          Previous
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={() => next && onPageChange(next)}
          disabled={!next || disabled}
        >
          Next
          <ChevronRight className="h-4 w-4 ml-1" />
        </Button>
      </div>
    </div>
  );
}
