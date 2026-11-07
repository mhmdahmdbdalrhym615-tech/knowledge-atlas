#!/usr/bin/env python3
"""Legacy setup script retired.

The original script recreated early scaffold files and could overwrite the
current Android implementation. It is intentionally non-destructive now.
Use the Gradle project and the documented Git branch instead.
"""

def main() -> None:
    print(
        "setup_app.py is retired and does not modify project files. "
        "Use Android Studio or the Gradle build instructions in README.md."
    )


if __name__ == "__main__":
    main()
