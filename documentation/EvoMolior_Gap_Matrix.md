# EvoMolior Gap Matrix

**Status:** Superseded historical verification matrix.

The maintained gap matrix is now part of
[`EvoMolior_Roadmap_and_Project_State.md`](EvoMolior_Roadmap_and_Project_State.md). Use that file
for current `Implemented`, `Partial`, `Disconnected`, `Unverified`, `Not Implemented`, and
`Working With Caveat` classifications.

This former matrix was retired on 2026-07-10 because several `satisfied` rows proved only that a
handler or component existed. Production-call-graph review found material counterexamples:

- normal production entry points execute agents in-process instead of selecting Shuru;
- `ConstitutionalEnforcer`, `AllowlistProxy`, and Watchdog budget telemetry are disconnected;
- privacy transformation is conditional on supplying a transform API key;
- grader groups are not full civilizations;
- recent capture-map and publication changes have deterministic tests but no fresh realistic run.

Historical audits and dated evidence remain in ignored internal development records.
