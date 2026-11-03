#!/usr/bin/env python3
"""The r33 static Kotlin lexer check (the r32 verification-pass pattern):
state-machine scan for bracket balance, unterminated strings and broken
comments on files the sandbox cannot compile (ADR-0013: GitHub Actions is
the only build channel, so the local gate is lexical + review).
Usage: python3 tools/kotlin_lex_check.py FILE [FILE...]"""
import sys


def check(path: str) -> str:
    try:
        text = open(path, encoding="utf-8").read()
    except OSError as e:
        return f"{path}: READ ERROR {e}"
    i, n = 0, len(text)
    stack = []
    line = 1
    state = "code"  # code | line_comment | block_comment | string | char | backtick
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
        if state == "code":
            if c == "/" and nxt == "/":
                state = "line_comment"
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = "block_comment"
                i += 2
                continue
            if c == '"':
                state = "string"
                i += 1
                continue
            if c == "'":
                state = "char"
                i += 1
                continue
            if c == "`":
                # A backtick identifier (test names): everything up to the
                # closing backtick is inert --- including apostrophes that
                # are NOT char literals ("the pipeline's frame").
                state = "backtick"
                i += 1
                continue
            if c in "([{":
                stack.append((c, line))
            elif c in ")]}":
                if not stack:
                    return f"{path}:{line}: unmatched '{c}'"
                o, ol = stack.pop()
                if o == "interp":
                    if c != "}":
                        return f"{path}:{line}: '{c}' closes string interpolation"
                    state = "string"  # ${...} expression ended: back inside the string
                elif "([{" [")]}".index(c)] != o:
                    return f"{path}:{line}: '{c}' closes '{o}' opened line {ol}"
        elif state == "line_comment":
            if c == "\n":
                state = "code"
        elif state == "block_comment":
            if c == "*" and nxt == "/":
                state = "code"
                i += 2
                continue
        elif state == "string":
            if c == "\\":
                i += 2
                continue
            if c == "\n":
                return f"{path}:{line}: unterminated string"
            if c == "$" and nxt == "{":
                stack.append(("interp", line))  # nested code inside interpolation
                state = "code"
                i += 2
                continue
            if c == '"':
                state = "code"
        elif state == "char":
            if c == "\\":
                i += 2
                continue
            if c == "'":
                state = "code"
        elif state == "backtick":
            if c == "\n":
                return f"{path}:{line}: unterminated backtick identifier"
            if c == "`":
                state = "code"
        i += 1
    if stack:
        return f"{path}: unclosed '{stack[-1][0]}' opened line {stack[-1][1]}"
    if state != "code":
        return f"{path}: ends mid-{state}"
    return f"{path}: OK"


if __name__ == "__main__":
    bad = 0
    for p in sys.argv[1:]:
        result = check(p)
        print(result)
        if not result.endswith(": OK"):
            bad += 1
    sys.exit(1 if bad else 0)
