"""Skip compilation only when every changed path is ordinary documentation."""
import sys


def needs_build(paths):
    documentation = {
        "README.md", "CHANGELOG.md", "CONTRIBUTING.md", "CODE_OF_CONDUCT.md",
        "SECURITY.md", "LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md",
    }
    return not paths or any(
        path not in documentation and not (
            path.startswith("docs/") and path.endswith(".md")
            and not path.startswith(("docs/generated/", "docs/adr/"))
        )
        for path in paths
    )


if __name__ == "__main__":
    paths = [p.decode("utf-8", errors="surrogateescape") for p in sys.stdin.buffer.read().split(b"\0") if p]
    print("build=" + str(needs_build(paths)).lower())
