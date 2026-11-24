import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { TablePagination } from "./table-pagination";

describe("TablePagination", () => {
  it("shows the count, turns off Previous with no link, and sends the Next link on a click", () => {
    const next = "https://fhir.example.org/fhir?_getpages=abc&_offset=2";
    const onPageChange = vi.fn();
    render(
      <TablePagination
        shown={2}
        total={5}
        pageSize={50}
        onPageSizeChange={vi.fn()}
        next={next}
        onPageChange={onPageChange}
      />,
    );

    expect(screen.getByText("Showing 2 of 5")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(onPageChange).toHaveBeenCalledWith(next);
  });
});
