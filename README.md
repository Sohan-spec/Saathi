# Saathi — Everyday Helper

Saathi is an Android-first (Kotlin) everyday assistant app designed to make common daily tasks easier, faster, and more organized. The project brings together a set of small, practical utilities ("helpers") behind a single, clean experience.

> **Branch:** `assisted-workflow` — this branch contains the work-in-progress workflow and features under active development.

## Table of contents

- [What is Saathi?](#what-is-saathi)
- [Key features](#key-features)
- [Tech stack](#tech-stack)
- [Repository structure](#repository-structure)
- [Getting started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Run the Android app](#run-the-android-app)
  - [Run Python utilities (if present)](#run-python-utilities-if-present)
- [Configuration](#configuration)
- [Build & release](#build--release)
- [Testing](#testing)
- [Contributing](#contributing)
- [Roadmap](#roadmap)
- [License](#license)

## What is Saathi?

**Saathi** (meaning *companion/helper*) is an "everyday helper" application that aims to bundle the kinds of tools people repeatedly need—reminders, quick notes, calculators/converters, and other small utilities—into one place.

The goal of the project is to:

- Reduce app switching for simple tasks
- Keep everyday information organized
- Provide a lightweight, friendly UX

## Key features

Because the project is evolving, exact features may differ by commit and module. The app is structured to support multiple helper modules such as:

- Notes and quick capture
- Reminders / basic task tracking
- Simple utilities (converters, calculators, etc.)
- Offline-first storage patterns (where applicable)

> If you’re on this branch, expect some features to be incomplete or in-progress.

## Tech stack

Language composition (approx.):

- **Kotlin** (~88%) — main Android application
- **HTML** (~9%) — static assets/docs or UI resources (if used)
- **Python** (~3%) — scripts or small supporting utilities

Typical Android stack components you may see in this repo include:

- Android SDK + Gradle
- Kotlin / AndroidX libraries
- Material Design UI components

(Exact dependencies are defined in the Gradle build files.)

## Repository structure

The exact layout depends on the current state of the branch, but commonly you will find:

- `app/` — main Android application module (Kotlin source, resources)
- `build.gradle(.kts)` / `settings.gradle(.kts)` — Gradle build configuration
- `gradlew`, `gradlew.bat`, `gradle/` — Gradle wrapper
- `*.py` / `scripts/` — Python scripts (if present)
- `*.html` — HTML assets (if present)

## Getting started

### Prerequisites

- **Android Studio** (latest stable recommended)
- **JDK** (Android Studio’s bundled JDK is fine)
- **Git**

Optional (only if you plan to run scripts):

- **Python 3.10+**

### Run the Android app

1. Clone the repository:

   ```bash
   git clone https://github.com/Sohan-spec/Saathi---Everyday-Helper.git
   cd Saathi---Everyday-Helper
   ```

2. Check out the branch:

   ```bash
   git checkout assisted-workflow
   ```

3. Open the project in **Android Studio**.

4. Let Gradle sync finish.

5. Run on an emulator or physical device:

   - Select the **app** run configuration
   - Press **Run**

### Run Python utilities (if present)

If the repository contains Python helper scripts, you can typically run them like:

```bash
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt  # only if present
python path/to/script.py
```

## Configuration

If the app requires API keys, URLs, or other environment-specific configuration, it is recommended to:

- Use `local.properties` (not committed) for machine-specific paths/keys
- Use Gradle `buildConfigField` / `resValue` for build-time configuration
- Avoid committing secrets to the repo

If you add secrets, prefer GitHub Actions secrets + runtime configuration.

## Build & release

From the project root:

- Debug build:
  ```bash
  ./gradlew assembleDebug
  ```

- Release build (requires signing configuration):
  ```bash
  ./gradlew assembleRelease
  ```

Generated APKs/AABs will be under the appropriate `app/build/outputs/` directories.

## Testing

Depending on the modules in this branch, you may have:

- Unit tests: `./gradlew test`
- Instrumentation tests: `./gradlew connectedAndroidTest`

## Contributing

Contributions are welcome.

- Create a new branch from `assisted-workflow`
- Keep PRs small and focused
- Include screenshots or screen recordings for UI changes
- Add/update tests when applicable

## Roadmap

Ideas commonly associated with an "everyday helper" app:

- Modular feature expansion (add more helper tools)
- Improved offline-first support
- Better theming & accessibility
- Backup/export options

## License

No license file has been specified in this branch yet.

If you intend the project to be open source, add a `LICENSE` file (e.g., MIT, Apache-2.0) and update this section accordingly.
