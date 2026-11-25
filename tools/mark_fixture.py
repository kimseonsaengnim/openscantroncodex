#!/usr/bin/env python3
"""Mark a test-only raster of an OpenScantron ten-question A–D answer sheet."""

import argparse
import csv
from pathlib import Path

import pymupdf

FIXTURE = Path(__file__).resolve().parents[1] / "app/src/test/resources/marks-0017.csv"


def mark_sheet(blank_pdf: Path, output: Path) -> None:
    with FIXTURE.open(encoding="utf-8", newline="") as file:
        marks = list(csv.DictReader(file))

    with pymupdf.open(blank_pdf) as document:
        page = document[0]
        if "10 questions" not in page.get_text():
            raise ValueError("Use a 10-question OpenScantron A–D sheet with four ID columns.")

        width, height = page.rect.width, page.rect.height

        def mark(x: float, y: float, radius: float, gray: int = 24) -> None:
            tone = (gray / 255,) * 3
            page.draw_circle((x * width, y * height), radius * width, width=0, color=None, fill=tone)

        for column, digit in enumerate("0017"):
            mark(.118 + column * .056, .133 + int(digit) * .0165, .0074 * .90)

        for row in marks:
            question = int(row["question"]) - 1
            for index, label in enumerate("ABCD"):
                truth = row["ground_truth"]
                if label in truth.split("|") or truth == f"{label} faint":
                    x = .084 + .84 * (.16 + (index + .5) * .79 / 4)
                    mark(x, .367 + question * .0214, .0080 * .90, 184 if "faint" in truth else 24)

        page.get_pixmap(matrix=pymupdf.Matrix(2.25, 2.25), alpha=False).save(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("blank_pdf", type=Path)
    parser.add_argument("output_png", type=Path)
    args = parser.parse_args()
    mark_sheet(args.blank_pdf, args.output_png)
