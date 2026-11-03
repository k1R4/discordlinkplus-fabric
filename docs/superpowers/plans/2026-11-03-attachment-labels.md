# Attachment labels implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this checkpoint task by task.

**Goal:** Replace incoming Discord upload URLs with compact labels while preserving captions and ordinary links.

**Architecture:** A small `DiscordAttachments` helper formats incoming text and JDA attachment metadata before the existing relay. Keep the relay and Minecraft rendering unchanged in this checkpoint; structured styles and replies belong to later steps.

**Tech stack:** Java 25, JDA 6.7.0, Fabric 26.3, Gradle wrapper, JUnit 5.

**Spec:** User's agreed feature 1: `[Image]`, `[GIF]`, `[Video]`, `[File: name]` or grouped counts; preserve text; handle GIF-picker links separately without stripping normal links.

## Decisions for this checkpoint

- Use individual labels, preserving upload order. Grouped counts are optional in the spec.
- Prefer MIME types, with case-insensitive filename extensions as fallback when the type is absent or generic.
- Recognize exact Tenor/Giphy hosts and GIF-specific paths. Do not fetch URLs or expand redirects.
- Keep labels as plain text for now; this checkpoint adds no click actions.
- Bound and clean filenames before inserting them into labels.
- Keep the current global incoming message limit. Reserve room for labels so a long caption does not hide the uploads.
- No voice, Minecraft-to-Discord, mod ID, attribution, dependency, or version changes.

## Review focus

Normal links (including nested/lookalike hosts), punctuation around picker links, media without MIME types, attachment-only messages, filenames with controls/formatting, and long captions must remain predictable.

## Task: Format incoming attachments and GIF-picker links

**Files:**
- Add `src/fabric/java/com/mcdiscordbot/fabric/DiscordAttachments.java` for classification and text formatting.
- Modify `ChatBridge.java` to invoke the helper.
- Extend `ChatBridgeTest.java` using real JDA attachments and existing in-memory message events.
- Update `CHAT-SETUP.md` with supported behavior and runtime test instructions.

- [x] Add tests, run the focused test class, and confirm failures show raw URLs/missing picker labels.
- [x] Implement classification and exact picker URL recognition; run the focused tests.
- [x] Check long captions, mixed uploads, filenames, normal URLs, and bot/webhook filtering.
- [x] Run the full `build`, review the diff, and create a separate local Git commit.
- [x] Report automated evidence separately from actual Discord round-trip validation.

## Verification record

- Baseline: commit `110c027`, 39 tests passing.
- Red: five new behavior tests failed against unchanged formatting; existing tests passed with baseline build permissions. The sandbox-only run also denied temporary icon directories; no production change was made for that environment restriction.
- Green: focused tests passed after implementing labels. An additional extensionless-file regression reproduced a null extension from JDA; normalization fixed it. Long-caption coverage passed.
- Independent review found one minor issue: closing Markdown around a picker URL could be consumed. Added a failing regression, preserved matching wrappers, and re-reviewed the fix with no blocking findings.
- Final full build: `build --console=plain` succeeded with 48 tests, no failures, errors, or skips.
- Actual Discord round trips have not been run for this checkpoint.

Ruling: implement directly in the existing clean `fabric-chat-polish` checkout created for this fork, keeping this single checkpoint local. This retains the user's established branch workflow without adding another checkout or publishing unfinished work.
