"""Summarise a reader log: per font size, pages / lines and the render check.

Usage: python summary.py <logfile>
"""
import re
import sys

path = sys.argv[1]
data = open(path, "rb").read().decode("utf-8", "replace")
for line in data.split("\n"):
    if "textLines=" in line:
        m = re.search(r"page (\d+) textLines=(\d+) rendered=(\d+) packed=(\d+)(\s*!! OVERFLOW)?", line)
        if m:
            print("   page %s: textLines %s rendered %s packed %s %s"
                  % (m.group(1), m.group(2), m.group(3), m.group(4),
                     "OVERFLOW" if m.group(5) else "ok"))
    if "paginated" in line:
        m = re.search(r"pages=(\d+).*?linesPerPage=(\d+).*?layoutLines=(\d+).*?sizeSp=(\d+)", line)
        if m:
            print("%ssp pages=%s linesPerPage=%s layoutLines=%s"
                  % (m.group(4), m.group(1), m.group(2), m.group(3)))
    if "cut page" in line:
        i = line.find("reader: cut")
        print("   " + line[i:].strip())
