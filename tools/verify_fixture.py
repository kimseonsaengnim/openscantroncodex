#!/usr/bin/env python3
"""Verify the fictional 0017 PDFs and CSVs exported by the integration test."""

import argparse
import csv
from pathlib import Path

import pymupdf


def read_records(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as file:
        return list(csv.DictReader(file))


def check(condition: bool, detail: str) -> None:
    if not condition:
        raise ValueError(detail)


def verify(folder: Path) -> None:
    wide = read_records(folder / "answers.csv")
    check(len(wide) == 1, "Expected one response row.")
    row = wide[0]
    expected = {
        "student_id": "0017",
        "Q008": "BLANK",
        "Q009": "INVALID",
        "Q010": "B",
        "correct_count": "7",
        "incorrect_count": "1",
        "blank_count": "1",
        "invalid_count": "1",
        "pending_count": "0",
        "score": "7.0",
        "max_score": "10.0",
        "percentage": "70.0",
        "passing_status": "PASS",
        "grading_status": "FINAL",
    }
    for (column, value) in expected.items():
        check(row.get(column) == value, f"{column}: expected {value!r}, found {row.get(column)!r}.")

    audit = read_records(folder / "audit.csv")
    check(len(audit) == 10, "Expected ten audit rows.")
    check(all(item["submission_id"] == row["submission_id"] for item in audit), "Audit ID mismatch.")
    check([item["classification"] for item in audit[-2:]] == ["MULTIPLE_MARKS", "AMBIGUOUS"],
          "Machine observations were not preserved.")
    check([item["effective_answer"] for item in audit[-2:]] == ["INVALID", "B"], "Wrong effective answers.")

    with pymupdf.open(folder / "archive.pdf") as archive:
        check(archive.page_count == 1, "Expected one archive page.")
        pixels = archive[0].get_pixmap()
        photo = pymupdf.Pixmap(str(folder / "0017.png"))
        scale = min((pixels.width - 32) / photo.width, (pixels.height - 32) / photo.height)
        left = (pixels.width - photo.width * scale) / 2
        top = (pixels.height - photo.height * scale) / 2
        for x, y in ((.055, .045), (.945, .045), (.945, .955), (.055, .955)):
            rgb = pixels.pixel(round(left + x * photo.width * scale), round(top + y * photo.height * scale))
            check(max(rgb) < 90, f"Registration square at {x}, {y} is missing in the archive.")
    with pymupdf.open(folder / "performance.pdf") as report:
        check(report.page_count == 1 and "Score 7 / 10" in report[0].get_text(), "Report text did not open.")
    print("Verified one final response for 0017, ten audit rows, four archived corners, and readable report text.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    verify(parser.parse_args().folder)
