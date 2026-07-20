# Final Submission Checklist

## Deadline

**Monday, July 20, 2026 at 5:00 PM EDT**  
Equivalent: **2:00 PM PDT / 21:00 UTC / 11:00 PM CEST**.

Do not wait for the last minutes to submit.

Official references:

- `https://qwencloud-hackathon.devpost.com/`
- `https://qwencloud-hackathon.devpost.com/rules`

## Repository Deliverables

| Status | Item | Verification |
|---|---|---|
| ✅ | Root OSI license | `LICENSE` contains `AGPL-3.0-only`; verify GitHub detects it after push |
| ✅ | Layered architecture | Deep HTML5 guide plus 1920×1080 SVG and PNG |
| ✅ | Track selected | Agent Society |
| ✅ | What / who / how description | `Devpost_Submission.md` |
| ✅ | Qwen Cloud named in description | Dedicated section and Built With list |
| ✅ | Judge guide | `Judge_Guide.md` |
| ✅ | Three-minute recording plan | `Demo_Video_Script.md` |
| ✅ | Alibaba proof runbook | `Alibaba_Cloud_Deployment_Proof.md` |
| ✅ | Slide deck | `EvoMolior_Pitch_Deck.pdf` plus editable HTML source |
| ✅ | Contribution and security guidance | root `CONTRIBUTING.md` and `SECURITY.md` |
| ⏳ | Crash-log prevention | Future JVM crash/replay logs are ignored; current worktree deletions must be staged in the curated release commit |
| ✅ | Mobile credential placeholder hygiene | tracked Firebase configuration contains placeholders only |
| ⏳ | Full multi-round Appendix-B acceptance | Parallel work; do not claim success before tracker closes |
| ➖ | New remediation step-16 matched comparison | Intentionally skipped; existing Qwen evidence used |

## Human / Account Actions

| Status | Required action |
|---|---|
| ⬜ | Finish Alibaba Cloud deployment |
| ⬜ | Capture Alibaba Cloud console screenshot |
| ⬜ | Capture running backend/dashboard screenshot |
| ⬜ | Make GitHub repository public |
| ⬜ | Confirm GitHub visibly detects AGPL-3.0 |
| ⬜ | Confirm no internal-only files are included in the release commit |
| ⬜ | Record and edit demo to under three minutes |
| ⬜ | Upload video publicly to YouTube or Vimeo |
| ⬜ | Verify video plays without login |
| ⬜ | Upload the required pitch deck PDF |
| ⬜ | Paste project description and Built With values |
| ⬜ | Add live URL, repository URL, and video URL |
| ⬜ | Ensure all teammates accept Devpost invitations |
| ⬜ | Submit before the deadline |

## Public-Release Commit Gate

Before pushing:

```bash
git status --short
git diff --check
git ls-files | grep -E '(^|/)(local\.properties|\.env|GoogleService-Info\.plist|google-services\.json)$'
```

Review the output rather than assuming ignored files are untracked. The repository intentionally
contains placeholder Firebase config files; no production values may remain.

Also verify:

- no API keys or provider responses containing secrets;
- no crash logs, temporary volumes, local paths, internal acceptance locks, or daemon state;
- no generated `build/` or unrestricted `results/` trees;
- the generated `results/` tree is excluded; public benchmark evidence lives in the sanitized
  `documentation/submission/evidence/` artifacts;
- README commands work from a fresh clone with JDK 17.

Run a full-history scanner such as Gitleaks or TruffleHog before making the repository public.

## Incognito Link Test

Open a fresh logged-out incognito window and verify:

1. repository root loads;
2. `LICENSE` is visible and detected;
3. architecture HTML opens and the architecture image renders;
4. pitch deck opens;
5. video plays without login;
6. live Alibaba Cloud URL resolves and uses HTTPS;
7. judge credentials work, if authentication is required;
8. no link points to localhost, a private filesystem path, or a private build artifact.

## Final Devpost Content Check

- project name: **EvoMolior**
- track: **Agent Society**
- description explicitly says **Qwen Cloud**
- Built With includes **Qwen Cloud** and **Alibaba Cloud**
- contest-period updates explain what changed between May 26 and July 20, 2026
- benchmark text includes sample-size and latency caveats
- no unfinished acceptance run is described as completed
- repository, deployment, and video URLs are final—not placeholders
