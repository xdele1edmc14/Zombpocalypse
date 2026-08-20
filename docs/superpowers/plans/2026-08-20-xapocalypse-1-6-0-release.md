# xApocalypse 1.6.0 Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish xApocalypse 1.6.0 with official Paper 26.2 and Java 25 metadata, synchronized documentation, and a verified release JAR.

**Architecture:** Keep the existing plugin implementation intact and treat this as a compatibility release. Synchronize the Maven build, Bukkit descriptor, bundled resource headers, public README, wiki pages, testing guide, and changelog before building the final artifact.

**Tech Stack:** Java 25, Maven, Paper API 26.2, Bukkit `plugin.yml`, Markdown documentation.

## Global Constraints

- Release version is exactly `1.6.0`.
- Supported server/API target is Paper `26.2`.
- Required Java version is `25`.
- Preserve existing gameplay and configuration behavior.
- Commit and push the completed release to the current `qa-bugfixes` branch.

---

### Task 1: Synchronize release and build metadata

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/messages.yml`

**Interfaces:**
- Consumes: Paper Maven coordinate `io.papermc.paper:paper-api:26.2.build.112-stable`.
- Produces: `target/xApocalypse-1.6.0.jar` built for Java 25 and declared against API 26.2.

- [x] **Step 1:** Run `py version_sync.py --version 1.6.0 --dry-run` and confirm every managed release reference is detected.
- [x] **Step 2:** Run `py version_sync.py --version 1.6.0` to apply the managed version changes without committing.
- [x] **Step 3:** Update `pom.xml` to Java 25, Paper API `26.2.build.112-stable`, Adventure `5.2.0`, and Mockito `5.23.0`.
- [x] **Step 4:** Update `src/main/resources/plugin.yml` to `api-version: 26.2`.
- [x] **Step 5:** Run `rg -n "1\\.5\\.2" pom.xml src/main/resources README.md docs` and classify any remaining matches as historical changelog text or missed current metadata.

### Task 2: Synchronize public documentation

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `README.md`
- Modify: `TESTING_GUIDE.md`
- Modify: `docs/Home.md`
- Modify: `docs/Getting-Started.md`
- Modify: `docs/PlaceholderAPI.md`
- Modify: `src/main/java/com/deleted/xapocalypse/AttributeResolver.java`

**Interfaces:**
- Consumes: release metadata from Task 1.
- Produces: consistent user-facing requirements and installation instructions for xApocalypse 1.6.0.

- [x] **Step 1:** Add a dated 1.6.0 changelog entry covering Paper 26.2, Java 25, dependency updates, and retained gameplay behavior.
- [x] **Step 2:** Update README badges, requirements, installation artifact, and build command for Java 25 and Paper 26.2.
- [x] **Step 3:** Update wiki home, getting-started, PlaceholderAPI, and testing-guide references.
- [x] **Step 4:** Generalize stale source comments that describe attribute compatibility as specific to 1.21.
- [x] **Step 5:** Search current release surfaces for stale `1.21`, `Java 21`, and `1.5.2` references; retain only explicitly historical changelog/bug records.

### Task 3: Verify, commit, and publish

**Files:**
- Verify: `target/xApocalypse-1.6.0.jar`
- Review: all modified files from Tasks 1 and 2

**Interfaces:**
- Consumes: synchronized source and documentation.
- Produces: one tested release commit pushed to `origin/qa-bugfixes`.

- [x] **Step 1:** Run Maven under Java 25 with `mvn.cmd clean package`.
- [x] **Step 2:** Confirm all eight tests pass and inspect the packaged `plugin.yml`, `config.yml`, and `messages.yml` inside the JAR.
- [x] **Step 3:** Run stale-reference searches and `git diff --check`.
- [x] **Step 4:** Review `git diff --stat`, `git diff`, and the exact staged file list.
- [x] **Step 5:** Commit with `release: update xApocalypse to 1.6.0` and push the tracking branch.
