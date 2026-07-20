package com.achulkov.evomolior.cli.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.max

private val remoteDashboardJson = Json { encodeDefaults = true }

/**
 * Renders the authenticated dashboard login screen.
 *
 * @param error Whether the preceding sign-in attempt failed.
 * @return A complete, self-contained HTML document.
 */
internal fun renderRemoteLogin(error: Boolean): String = remotePage("Sign in") {
    """
    <main class="auth-shell">
      <section class="auth-card" aria-labelledby="login-title">
        <p class="eyebrow">EvoMolior / Remote operations</p>
        <h1 id="login-title">Command the fleet.</h1>
        <p class="lede">A private control plane for launches, evidence, and long-running intelligence.</p>
        ${if (error) """<p class="notice danger" role="alert">The username or password was not accepted.</p>""" else ""}
        <form action="/login" method="post" class="stack">
          <label>Username<input name="username" autocomplete="username" required autofocus></label>
          <label>Password<input name="password" type="password" autocomplete="current-password" required></label>
          <button class="button primary" type="submit">Enter control room <span aria-hidden="true">→</span></button>
        </form>
      </section>
    </main>
    """
}

/**
 * Renders the fleet overview and embeds its first observability snapshot.
 *
 * @param initial Initial server-side snapshot for progressive enhancement.
 * @return A complete, self-contained HTML document.
 */
internal fun renderRemoteDashboard(initial: RemoteDashboardOverview): String = remotePage("Fleet overview") {
    val stats = initial.stats
    """
    <header class="topbar">
      <a class="brand" href="/" aria-label="EvoMolior dashboard"><span class="brand-mark">E</span><span>EvoMolior <b>Remote</b></span></a>
      <div class="connection" data-live-state role="status" aria-live="polite"><i></i><span>Connecting live feed…</span></div>
      <form action="/logout" method="post"><button class="button quiet" type="submit">Sign out</button></form>
    </header>
    <main class="shell" data-dashboard-overview>
      <section class="page-intro">
        <div><p class="eyebrow">Fleet observability</p><h1>Every run, legible.</h1><p class="lede">Launch work, inspect proof, and follow the system as it evolves.</p></div>
        <p class="timestamp">Snapshot <time data-last-update>${html(initial.generatedAt)}</time></p>
      </section>
      <section class="kpis" aria-label="Fleet key performance indicators">
        ${kpi("Total runs", stats.totalRuns.toString(), "Recorded fleet executions")}
        ${kpi("Active now", stats.activeRuns.toString(), "Queued or currently running")}
        ${kpi("Successful", stats.succeededRuns.toString(), ratioPercent(stats.successRate))}
        ${kpi("Failed", stats.failedRuns.toString(), "Terminal failures")}
        ${kpi("Runtime", duration(stats.completedDurationMillis), "Completed work")}
        ${kpi("Throughput", stats.completedRunsPerRuntimeHour?.let { number(it, "runs/runtime h") } ?: "Unavailable", "Completed runs per aggregate runtime hour")}
        ${kpi("Output", bytes(stats.outputBytes), "${stats.artifactCount}${if (stats.artifactTotalsPartial) "+" else ""} artifacts")}
      </section>
      <section class="overview-grid">
        <article class="panel launch-panel">
          <div class="section-heading"><div><p class="eyebrow">Dispatch</p><h2>Launch a run</h2></div><span class="safe">Keys stay server-side</span></div>
          <form action="/runs" method="post" class="launch-form" data-launch-form>
            <label>Workload
              <select name="type" data-run-type><option value="run">Research run</option><option value="benchmark">Benchmark</option></select>
            </label>
            <label>Model backend
              <select name="backend"><option value="qwen-cloud">Qwen Cloud</option><option value="openrouter">OpenRouter</option><option value="codex-chatgpt">Codex / ChatGPT</option></select>
            </label>
            <label class="run-only full">Objective
              <textarea name="prompt" rows="5" maxlength="512000" placeholder="Describe the question, constraints, and desired output."></textarea>
            </label>
            <label class="benchmark-only">Benchmark
              <select name="benchmark"><option value="frames">Frames</option><option value="litqa2">LitQA2</option><option value="scholarqa">ScholarQA / ArxivDIGESTables</option><option value="scholarqabench">ScholarQABench QASA</option><option value="scholarqabench-pubmed">ScholarQABench PubMedQA</option><option value="scholarqabench-scifact">ScholarQABench SciFact</option><option value="scholarqabench-cs">ScholarQABench CS</option><option value="ai2-scholar-qa">AI2 ScholarQA</option><option value="autoresearchbench">AutoResearchBench</option></select>
            </label>
            <label class="benchmark-only">Runner
              <select name="runner"><option value="evomolior-society">EvoMolior society</option><option value="single-agent">Single-agent baseline</option></select>
            </label>
            <label class="benchmark-only">Task limit<input name="limit" type="number" min="1" max="50" value="1"></label>
            <label class="benchmark-only">Offset<input name="offset" type="number" min="0" value="0"></label>
            <label class="toggle full"><input name="fast" type="checkbox" checked><span><b>Fast mode</b><small>Use the fast execution profile</small></span></label>
            <button class="button primary full" type="submit">Launch workload <span aria-hidden="true">↗</span></button>
          </form>
        </article>
        <article class="panel chart-panel">
          <div class="section-heading"><div><p class="eyebrow">Fleet shape</p><h2>Run status</h2></div></div>
          <div class="chart-wrap" data-status-chart>${distributionChart(initial.statusDistribution, "Status distribution")}</div>
          <div class="legend" data-status-legend>${distributionLegend(initial.statusDistribution)}</div>
        </article>
        <article class="panel chart-panel">
          <div class="section-heading"><div><p class="eyebrow">Workload mix</p><h2>Run types</h2></div></div>
          <div class="chart-wrap" data-type-chart>${distributionChart(initial.typeDistribution, "Run type distribution")}</div>
          <div class="legend" data-type-legend>${distributionLegend(initial.typeDistribution)}</div>
        </article>
      </section>
      <section class="panel runs-panel">
        <div class="section-heading table-heading"><div><p class="eyebrow">Run ledger</p><h2>Recent executions</h2></div>
          <div class="filters" aria-label="Filter runs">
            <label class="sr-only" for="run-search">Search runs</label><input id="run-search" type="search" placeholder="Search ID, backend…" data-filter-search>
            <label class="sr-only" for="status-filter">Status</label><select id="status-filter" data-filter-status><option value="">All statuses</option><option>QUEUED</option><option>RUNNING</option><option>SUCCEEDED</option><option>FAILED</option></select>
            <label class="sr-only" for="type-filter">Type</label><select id="type-filter" data-filter-type><option value="">All types</option><option value="run">Research run</option><option value="benchmark">Benchmark</option></select>
          </div>
        </div>
        <div class="table-scroll"><table><caption class="sr-only">Recent EvoMolior runs</caption><thead><tr><th>Run</th><th>State</th><th>Execution</th><th>Progress</th><th>Elapsed</th><th>Artifacts</th><th>Score</th><th>Actions</th></tr></thead>
          <tbody data-runs-table>${overviewRows(initial.runs)}</tbody></table></div>
      </section>
      <p class="sr-only" data-update-announcer aria-live="polite"></p>
    </main>
    ${snapshotScript("overview-snapshot", initial)}
    ${overviewScript()}
    """
}

/**
 * Renders a run detail screen and embeds its first observability snapshot.
 *
 * @param initial Initial server-side detail snapshot for progressive enhancement.
 * @return A complete, self-contained HTML document.
 */
internal fun renderRemoteRunDetail(initial: RemoteRunDetail): String = remotePage("Run ${initial.record.id}") {
    val record = initial.record
    val summary = initial.summary
    """
    <header class="topbar"><a class="brand" href="/" aria-label="Back to EvoMolior dashboard"><span class="brand-mark">E</span><span>EvoMolior <b>Remote</b></span></a>
      <div class="connection" data-live-state role="status" aria-live="polite"><i></i><span>Connecting live feed…</span></div><a class="button quiet" href="/">Fleet overview</a></header>
    <main class="shell run-detail" data-run-id="${html(record.id)}">
      <section class="page-intro detail-intro"><div><p class="eyebrow">Run / ${html(record.type)}</p><h1>${html(record.id)}</h1><p class="lede"><span class="status ${html(summary.status.name.lowercase())}" data-run-status>${html(summary.status.name)}</span> <span data-run-phase>${html(summary.phase)}</span></p></div>
        <p class="timestamp">Updated <time data-last-update>${html(initial.generatedAt)}</time></p></section>
      <section class="detail-grid">
        <article class="panel identity"><div class="section-heading"><div><p class="eyebrow">Execution identity</p><h2>Configuration</h2></div></div>
          <dl class="facts">
            ${fact("Backend", record.launch?.backend ?: "Not recorded")}${fact("Runner", record.launch?.runner ?: "Not recorded")}
            ${fact("Fast mode", record.launch?.fast?.let { if (it) "Enabled" else "Disabled" } ?: "Not recorded")}${fact("Benchmark", record.launch?.benchmark ?: "—")}
            ${fact("Limit / offset", listOfNotNull(record.launch?.limit?.toString(), record.launch?.offset?.toString()).joinToString(" / ").ifBlank { "—" })}
            ${fact("Started", record.startedAt)}${fact("Completed", record.completedAt ?: "In progress")}
            ${fact("Exit code", record.exitCode?.toString() ?: "Unavailable")}${fact("Output", record.outputDir)}
          </dl>
          <details><summary>Masked command</summary><code>${html(record.command.joinToString(" "))}</code></details>
        </article>
        <article class="panel progress-panel"><div class="section-heading"><div><p class="eyebrow">Live lifecycle</p><h2 data-run-phase>${html(summary.phase)}</h2></div><strong data-run-progress>${summary.progressPercent?.let(::percent) ?: "—"}</strong></div>
          <div class="progress" aria-label="Run progress"><i data-progress-bar style="width:${summary.progressPercent?.coerceIn(0.0, 100.0) ?: 0}%"></i></div>
          <dl class="compact-facts"><div><dt>Elapsed</dt><dd data-run-elapsed>${duration(summary.elapsedMillis)}</dd></div><div><dt>Activity</dt><dd>${html(summary.latestActivityAt ?: "Awaiting output")}</dd></div><div><dt>Error</dt><dd class="error-text">${html(summary.error ?: "None reported")}</dd></div></dl>
        </article>
      </section>
      <section class="panel metrics-panel"><div class="section-heading"><div><p class="eyebrow">Observable output</p><h2>Run metrics</h2></div><span class="muted">Absent values are never inferred.</span></div><div data-metrics>${metricGroups(initial.metrics)}</div></section>
      <section class="two-up">
        <article class="panel"><div class="section-heading"><div><p class="eyebrow">Temporal signals</p><h2>Activity</h2></div></div><div class="activity-chart" data-activity-chart>${activityChart(initial.series)}</div></article>
        <article class="panel"><div class="section-heading"><div><p class="eyebrow">Lifecycle record</p><h2>Event timeline</h2></div></div><ol class="timeline" data-events>${eventItems(initial.events)}</ol></article>
      </section>
      <section class="two-up">
        <article class="panel"><div class="section-heading"><div><p class="eyebrow">Bounded telemetry</p><h2>Safe live log</h2><a class="text-link" href="/logs/${url(record.id)}">Open safe tail</a></div><pre class="log" data-log>${html(initial.log.lines.joinToString("\n")).ifBlank { "No safe structural log output has been written yet." }}</pre><p class="muted" data-log-note>${logNote(initial.log)}</p></article>
        <article class="panel"><div class="section-heading"><div><p class="eyebrow">Quality evaluation</p><h2>Benchmark &amp; comparison</h2></div><div data-benchmark>${benchmarkPanel(initial.benchmark, initial.comparison)}</div></article>
      </section>
      <section class="panel"><div class="section-heading"><div><p class="eyebrow">Persisted output</p><h2>Artifact explorer</h2></div><div class="legend" data-artifact-mix>${distributionLegend(initial.artifactKinds)}</div></div><div class="table-scroll"><table><caption class="sr-only">Run artifacts</caption><thead><tr><th>Artifact</th><th>Kind</th><th>Size</th><th>Modified</th><th>Actions</th></tr></thead><tbody data-artifacts>${artifactRows(record.id, initial.artifacts)}</tbody></table></div><pre class="preview" data-preview hidden></pre></section>
      <section class="warnings" data-warnings>${warningItems(initial.warnings)}</section>
      <p class="sr-only" data-update-announcer aria-live="polite"></p>
    </main>
    ${snapshotScript("run-snapshot", initial)}
    ${detailScript(record.id)}
    """
}

/**
 * Renders a standalone operator-facing invalid request page.
 *
 * @param message Safe explanation of why the request could not be accepted.
 * @return A complete, self-contained HTML document.
 */
internal fun renderRemoteBadRequest(message: String): String = remotePage("Request rejected") {
    """<main class="auth-shell"><section class="auth-card"><p class="eyebrow">Request rejected</p><h1>Check the launch inputs.</h1><p class="notice danger">${html(message)}</p><a class="button primary" href="/">Return to dashboard</a></section></main>"""
}

private fun remotePage(title: String, body: () -> String): String = """
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${html(title)} · EvoMolior</title>
<style>
:root{color-scheme:dark;--ink:#edf3ff;--muted:#94a5bd;--bg:#07101d;--panel:#0d1a2bdb;--line:#7894b533;--cyan:#4de3d2;--blue:#78a6ff;--violet:#ae8cff;--good:#66e5a6;--amber:#ffca70;--red:#ff8295}*{box-sizing:border-box}body{margin:0;color:var(--ink);background:radial-gradient(circle at 8% -10%,#214876 0,transparent 28rem),radial-gradient(circle at 90% 0,#3e236e 0,transparent 32rem),var(--bg);font:15px/1.5 ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}a{color:var(--cyan);text-decoration:none}a:hover{text-decoration:underline}h1,h2,p{margin-top:0}h1{font-size:clamp(2rem,5vw,4.25rem);line-height:1.02;letter-spacing:-.055em}h2{font-size:1.22rem;letter-spacing:-.025em}.shell{max-width:1460px;margin:auto;padding:2rem clamp(1rem,4vw,3.5rem) 4rem}.topbar{display:flex;align-items:center;gap:1rem;justify-content:space-between;padding:1rem clamp(1rem,4vw,3.5rem);border-bottom:1px solid var(--line);background:#081322b8;backdrop-filter:blur(18px);position:sticky;top:0;z-index:2}.brand{display:flex;align-items:center;gap:.65rem;color:var(--ink);font-weight:700}.brand b{color:var(--cyan)}.brand-mark{display:grid;place-items:center;width:2rem;height:2rem;border-radius:.65rem;background:linear-gradient(135deg,var(--cyan),var(--violet));color:#081322}.connection{margin-left:auto;display:flex;gap:.5rem;align-items:center;color:var(--muted);font-size:.82rem}.connection i{width:.55rem;height:.55rem;border-radius:50%;background:var(--amber);box-shadow:0 0 0 .2rem #ffca7020}.connection.connected i{background:var(--good);box-shadow:0 0 0 .2rem #66e5a620}.eyebrow{margin-bottom:.45rem;color:var(--cyan);font-size:.7rem;font-weight:800;letter-spacing:.16em;text-transform:uppercase}.lede,.muted,.timestamp{color:var(--muted)}.timestamp{font-size:.82rem}.page-intro{display:flex;justify-content:space-between;align-items:end;gap:2rem;margin:2.6rem 0 2rem}.page-intro .lede{max-width:43rem;font-size:1.05rem}.kpis{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:.8rem;margin-bottom:1rem}.kpi,.panel{border:1px solid var(--line);background:linear-gradient(145deg,#122238e8,#0b1627e8);box-shadow:0 1rem 3rem #01050e33}.kpi{padding:1rem;border-radius:1rem}.kpi b{display:block;font-size:1.55rem;letter-spacing:-.04em}.kpi small{color:var(--muted)}.overview-grid,.detail-grid,.two-up{display:grid;grid-template-columns:1.5fr 1fr 1fr;gap:1rem;margin-bottom:1rem}.detail-grid,.two-up{grid-template-columns:1.2fr .8fr}.panel{border-radius:1.15rem;padding:1.25rem}.section-heading{display:flex;align-items:start;justify-content:space-between;gap:1rem;margin-bottom:1rem}.section-heading h2,.section-heading .eyebrow{margin-bottom:0}.safe{color:var(--good);font-size:.75rem}.launch-form{display:grid;grid-template-columns:1fr 1fr;gap:.85rem}.full{grid-column:1/-1}label{display:grid;gap:.38rem;font-size:.8rem;font-weight:700;color:#c4d2e8}input,select,textarea{min-width:0;width:100%;border:1px solid var(--line);border-radius:.65rem;background:#071321;color:var(--ink);padding:.72rem;font:inherit}textarea{resize:vertical}.toggle{display:flex;align-items:center;gap:.65rem;border:1px solid var(--line);padding:.7rem;border-radius:.65rem}.toggle input{width:auto}.toggle small{display:block;color:var(--muted);font-weight:400}.button{display:inline-flex;justify-content:center;align-items:center;gap:.55rem;border:1px solid transparent;border-radius:.65rem;padding:.7rem 1rem;color:var(--ink);font:700 .85rem inherit;cursor:pointer;text-decoration:none}.button:hover{text-decoration:none;filter:brightness(1.1)}.primary{background:linear-gradient(135deg,#276eed,#7d58df)}.quiet{background:transparent;border-color:var(--line)}.chart-wrap svg,.activity-chart svg{display:block;width:100%;height:auto}.legend{display:flex;flex-wrap:wrap;gap:.5rem;margin-top:.5rem}.legend span{font-size:.76rem;color:var(--muted)}.legend i{display:inline-block;width:.55rem;height:.55rem;border-radius:50%;margin-right:.28rem}.runs-panel{margin-top:1rem}.table-heading{align-items:end}.filters{display:flex;gap:.45rem;flex-wrap:wrap}.filters input,.filters select{width:auto;padding:.55rem;font-size:.8rem}.table-scroll{overflow:auto}table{border-collapse:collapse;width:100%;min-width:760px}th,td{padding:.8rem .65rem;border-top:1px solid var(--line);text-align:left;vertical-align:top}th{color:var(--muted);font-size:.67rem;text-transform:uppercase;letter-spacing:.1em}.run-name{font-weight:700}.subtle{display:block;color:var(--muted);font-size:.75rem}.status{display:inline-flex;padding:.23rem .5rem;border-radius:99px;font-size:.7rem;font-weight:800;letter-spacing:.04em;background:#7894b520}.status.running,.status.queued{color:var(--amber);background:#ffca7018}.status.succeeded{color:var(--good);background:#66e5a618}.status.failed{color:var(--red);background:#ff829518}.mini-progress,.progress{height:.42rem;background:#7894b522;border-radius:99px;overflow:hidden;min-width:5rem}.mini-progress i,.progress i{display:block;height:100%;background:linear-gradient(90deg,var(--cyan),var(--blue));border-radius:inherit}.facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1rem}.facts dt,.compact-facts dt{color:var(--muted);font-size:.7rem;text-transform:uppercase;letter-spacing:.07em}.facts dd{margin:.25rem 0 0;overflow-wrap:anywhere}.identity details{margin-top:1rem;color:var(--muted)}.identity code{display:block;margin-top:.6rem;white-space:pre-wrap;overflow-wrap:anywhere}.progress{margin:1rem 0}.compact-facts{display:grid;grid-template-columns:repeat(3,1fr);gap:.65rem}.compact-facts dd{margin:.25rem 0}.error-text{color:var(--red)}.metric-groups{display:grid;grid-template-columns:repeat(auto-fit,minmax(185px,1fr));gap:.75rem}.metric-group{border:1px solid var(--line);border-radius:.85rem;padding:.8rem}.metric-group h3{margin:0 0 .55rem;font-size:.75rem;color:var(--cyan);text-transform:uppercase;letter-spacing:.09em}.metric{padding:.45rem 0;border-top:1px solid #7894b51c}.metric:first-of-type{border-top:0}.metric b{display:block;font-size:1.08rem}.metric small{color:var(--muted)}.unavailable b{color:var(--muted);font-size:.9rem}.timeline{margin:0;padding:0;list-style:none;max-height:24rem;overflow:auto}.timeline li{position:relative;padding:0 0 1rem 1.3rem;border-left:1px solid var(--line)}.timeline li:before{content:"";position:absolute;left:-.27rem;top:.25rem;width:.45rem;height:.45rem;background:var(--cyan);border-radius:50%}.timeline time{display:block;font-size:.72rem;color:var(--muted)}.timeline strong{display:block}.log,.preview{margin:0;max-height:22rem;overflow:auto;padding:1rem;border-radius:.7rem;background:#05101dd9;border:1px solid var(--line);white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace}.text-link{font-size:.8rem}.artifact-actions{display:flex;gap:.65rem}.warnings{display:grid;gap:.6rem;margin-top:1rem}.notice{margin:0;padding:.85rem;border:1px solid var(--line);border-radius:.7rem}.danger{color:#ffd2da;background:#ff829513;border-color:#ff829544}.auth-shell{min-height:100vh;display:grid;place-items:center;padding:1rem}.auth-card{width:min(31rem,100%);padding:2rem;border:1px solid var(--line);border-radius:1.25rem;background:#0d1a2beb;box-shadow:0 2rem 5rem #0008}.stack{display:grid;gap:1rem}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}@media(max-width:1050px){.kpis{grid-template-columns:repeat(3,1fr)}.overview-grid{grid-template-columns:1fr 1fr}.launch-panel{grid-column:1/-1}}@media(max-width:720px){.topbar{padding:.8rem 1rem}.connection{display:none}.shell{padding:1.5rem 1rem 3rem}.page-intro{display:block;margin:1.5rem 0}.kpis,.overview-grid,.detail-grid,.two-up,.facts,.compact-facts,.launch-form{grid-template-columns:1fr}.chart-panel{grid-column:auto}.table-heading{display:block}.filters{margin-top:1rem}.detail-intro h1{font-size:1.8rem}}
</style>
<style>
.series-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(12rem,1fr));gap:.75rem}.series-card{border:1px solid var(--line);border-radius:.85rem;padding:.8rem;background:#07132180}.series-card h3{margin:0;font-size:.82rem}.series-card small,.series-card p{color:var(--muted)}.series-card svg{margin:.5rem 0}.series-card.unavailable{display:grid;min-height:9rem;align-content:center}.comparison{margin-top:1.2rem;padding-top:1rem;border-top:1px solid var(--line)}.comparison h3{display:flex;justify-content:space-between;gap:1rem}.comparison h3 small{color:var(--muted);font-weight:400}.comparison-cues{display:grid;gap:.5rem;padding:0;list-style:none}.comparison-cues li{display:grid;grid-template-columns:1fr auto;gap:.25rem .7rem;padding:.65rem;border:1px solid var(--line);border-radius:.7rem}.comparison-cues small{grid-column:1/-1;color:var(--muted)}
</style></head><body>${body()}</body></html>
""".trimIndent()

private fun kpi(label: String, value: String, note: String) = """<article class="kpi" data-kpi="${html(label)}"><small>${html(label)}</small><b>${html(value)}</b><small>${html(note)}</small></article>"""
private fun fact(label: String, value: String) = """<div><dt>${html(label)}</dt><dd>${html(value)}</dd></div>"""
private fun overviewRows(runs: List<RemoteRunSummary>): String = runs.joinToString("") { run ->
    """<tr data-run-row data-status="${html(run.status.name)}" data-type="${html(run.type)}" data-search="${html(listOfNotNull(run.id, run.type, run.backend, run.runner, run.benchmark).joinToString(" ").lowercase())}"><td><span class="run-name">${html(run.id)}</span><span class="subtle">${html(run.latestActivityAt ?: run.startedAt)}</span></td><td><span class="status ${html(run.status.name.lowercase())}">${html(run.status.name)}</span><span class="subtle">${html(run.phase)}</span></td><td>${html(run.type)}<span class="subtle">${html(listOfNotNull(run.backend, run.runner).joinToString(" · ").ifBlank { "Not recorded" })}</span></td><td>${progressCell(run.progressPercent)}</td><td>${html(duration(run.elapsedMillis))}</td><td>${html("${run.artifactCount}${if (run.artifactInventoryTruncated) "+" else ""} · ${bytes(run.outputBytes)}")}</td><td>${html(run.primaryScore?.let(::ratioPercent) ?: "—")}</td><td><a href="/runs/${url(run.id)}">Inspect</a></td></tr>"""
}.ifBlank { """<tr><td colspan="8" class="muted">No runs have been recorded. Dispatch the first workload above.</td></tr>""" }

private fun progressCell(value: Double?): String = if (value == null) "—" else """<div class="mini-progress"><i style="width:${value.coerceIn(0.0, 100.0)}%"></i></div><span class="subtle">${percent(value)}</span>"""
private fun distributionChart(values: List<RemoteDistributionValue>, label: String): String {
    val total = values.sumOf(RemoteDistributionValue::value).coerceAtLeast(1)
    var offset = 0.0
    val colors = listOf("#4de3d2", "#78a6ff", "#ae8cff", "#ffca70", "#ff8295")
    val segments = values.mapIndexed { index, item ->
        val length = item.value * 100.0 / total
        val result = """<circle cx="50" cy="50" r="36" fill="none" stroke="${colors[index % colors.size]}" stroke-width="14" pathLength="100" stroke-dasharray="$length ${100 - length}" stroke-dashoffset="${-offset}" transform="rotate(-90 50 50)"><title>${html("${item.label}: ${item.value}")}</title></circle>"""
        offset += length
        result
    }.joinToString("")
    return """<svg viewBox="0 0 100 100" role="img" aria-label="${html(label)}">$segments<text x="50" y="48" text-anchor="middle" fill="#edf3ff" font-size="18" font-weight="700">${values.sumOf(RemoteDistributionValue::value)}</text><text x="50" y="62" text-anchor="middle" fill="#94a5bd" font-size="7">runs</text></svg>"""
}
private fun distributionLegend(values: List<RemoteDistributionValue>): String {
    val colors = listOf("#4de3d2", "#78a6ff", "#ae8cff", "#ffca70", "#ff8295")
    return values.mapIndexed { index, value ->
        """<span><i style="background:${colors[index % colors.size]}"></i>${html(value.label)} ${value.value}</span>"""
    }.joinToString("").ifBlank { "<span>No observations yet.</span>" }
}
private fun metricGroups(metrics: List<RemoteMetric>): String = metrics.groupBy(RemoteMetric::category).toSortedMap().entries.joinToString("", """<div class="metric-groups">""", "</div>") { (category, values) ->
    """<section class="metric-group"><h3>${html(category)}</h3>${values.joinToString("") { metric ->
        val available = metric.numericValue != null
        """<div class="metric ${if (available) "" else "unavailable"}"><small title="${html(metric.description)}">${html(metric.label)}</small><b>${if (available) html(if (metric.unit == "ratio") ratioPercent(metric.numericValue) else number(metric.numericValue!!, metric.unit)) else "Unavailable"}</b><small>${html(if (available) metric.provenance ?: metric.availability else if (metric.availability == "INVALID") "Invalid value rejected" else "${metric.availability}: not emitted")}</small></div>"""
    }}</section>"""
}
private fun activityChart(series: List<RemoteMetricSeries>): String {
    return """<div class="series-grid">${series.joinToString("") { seriesChart(it) }}</div>"""
}

private fun seriesChart(series: RemoteMetricSeries): String {
    if (series.points.isEmpty()) {
        return """<section class="series-card unavailable"><h3>${html(series.label)}</h3><p>Not emitted</p><small>${html(series.unit)}</small></section>"""
    }
    val maxValue = max(1.0, series.points.maxOf(RemoteMetricPoint::value))
    val coordinates = series.points.mapIndexed { pointIndex, point ->
        "${pointIndex * 100.0 / max(1, series.points.lastIndex)},${92 - point.value * 82 / maxValue}"
    }.joinToString(" ")
    return """
        <section class="series-card">
          <h3>${html(series.label)}</h3>
          <svg viewBox="0 0 100 100" role="img" aria-label="${html("${series.label} in ${series.unit}")}">
            <path d="M0 92H100M0 51H100M0 10H100" stroke="#7894b544" stroke-width=".5"/>
            <polyline fill="none" stroke="#4de3d2" stroke-width="2" vector-effect="non-scaling-stroke" points="$coordinates"/>
          </svg>
          <small>${html(number(series.points.last().value, series.unit))} latest</small>
        </section>
    """.trimIndent()
}

private fun eventItems(events: List<RemoteRunEvent>) = events.joinToString("") { """<li><time>${html(it.timestamp ?: "Time unavailable")}</time><strong>${html(it.title)}</strong><span>${html(it.detail)}</span></li>""" }.ifBlank { """<li><strong>No lifecycle events emitted yet.</strong></li>""" }
private fun benchmarkPanel(
    benchmark: RemoteBenchmarkSummary?,
    comparison: RemoteComparisonSummary?
): String = buildString {
    if (benchmark == null) {
        append("""<p class="muted">No benchmark report was emitted for this run.</p>""")
    } else {
        append("""<dl class="compact-facts"><div><dt>Benchmark</dt><dd>${html(benchmark.benchmark ?: "—")}</dd></div><div><dt>Accuracy</dt><dd>${html(benchmark.accuracy?.let(::ratioPercent) ?: "Unavailable")}</dd></div><div><dt>Correct / total</dt><dd>${html(listOfNotNull(benchmark.correct?.toString(), benchmark.total?.toString()).joinToString(" / ").ifBlank { "Unavailable" })}</dd></div></dl>""")
        append("""<div class="table-scroll"><table><caption class="sr-only">Benchmark task results</caption><thead><tr><th>Task</th><th>Score</th><th>Latency</th><th>Result</th></tr></thead><tbody>""")
        append(benchmark.tasks.joinToString("") {
            """<tr><td>${html(it.taskId)}</td><td>${html(it.score?.let { score -> number(score, null) } ?: "—")}</td><td>${html(it.latencyMillis?.let(::duration) ?: "—")}</td><td>${html(it.error ?: it.correct?.let { correct -> if (correct) "Correct" else "Incorrect" } ?: "Unavailable")}</td></tr>"""
        })
        append("</tbody></table></div>")
    }
    append(comparisonPanel(comparison))
}

private fun comparisonPanel(comparison: RemoteComparisonSummary?): String {
    if (comparison == null) return """<p class="muted">No matched baseline comparison was emitted.</p>"""
    val cues = comparison.comparisons.joinToString("") { cue ->
        val label = cue.interpretation.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
        """<li><strong>${html(cue.comparatorArm)}</strong><span class="status">${html(label)}</span><small>${html(cue.meanDifference?.let { number(it, "quality Δ") } ?: "No paired quality estimate")}${cue.lower95?.let { " · 95% CI ${number(it, null)} to ${number(cue.upper95 ?: it, null)}" }.orEmpty()}</small></li>"""
    }.ifBlank { "<li>No comparable paired cells were emitted.</li>" }
    val arms = comparison.arms.joinToString("") { arm ->
        """<tr><td>${html(arm.arm)}</td><td>${html(arm.meanQuality?.let { number(it, null) } ?: "—")}</td><td>${html(arm.meanCostUsd?.let { number(it, "USD") } ?: "—")}</td><td>${html(arm.meanLatencyMillis?.let { duration(it.toLong()) } ?: "—")}</td><td>${html("${arm.successCount ?: 0}/${arm.cellCount ?: 0}")}</td></tr>"""
    }
    return """
        <section class="comparison">
          <h3>Matched comparison <small>baseline ${html(comparison.baselineArm)}</small></h3>
          <ul class="comparison-cues">$cues</ul>
          <div class="table-scroll"><table><caption class="sr-only">Comparison arm metrics</caption><thead><tr><th>Arm</th><th>Quality</th><th>Cost</th><th>Latency</th><th>Success</th></tr></thead><tbody>$arms</tbody></table></div>
        </section>
    """.trimIndent()
}

private fun artifactRows(id: String, artifacts: List<RemoteArtifact>) = artifacts.joinToString("") { artifact ->
    val path = urlPath(artifact.relativePath)
    val download = "/outputs/${url(id)}/$path"
    """<tr><td>${html(artifact.relativePath)}</td><td>${html(artifact.kind)}</td><td>${html(bytes(artifact.sizeBytes))}</td><td>${html(artifact.modifiedAt)}</td><td class="artifact-actions">${if (artifact.previewable) """<button class="text-link preview-button" type="button" data-preview-path="${html(artifact.relativePath)}">Preview</button>""" else ""}${if (artifact.downloadable) """<a href="$download" download>Download</a>""" else """<span class="subtle">Restricted</span>"""}</td></tr>"""
}.ifBlank { """<tr><td colspan="5" class="muted">This run has not written safe artifacts yet.</td></tr>""" }
private fun logNote(log: RemoteLogTail): String = buildString {
    append("${bytes(log.sizeBytes)} log. ")
    append(if (log.truncated) "Tail is bounded. " else "")
    if (log.withheldLines > 0) append("${log.withheldLines} unstructured line(s) withheld for privacy.")
}.ifBlank { "Only structural lifecycle lines are shown." }
private fun warningItems(warnings: List<String>) = warnings.joinToString("") { """<p class="notice danger">Warning: ${html(it)}</p>""" }
private inline fun <reified T> snapshotScript(id: String, value: T): String = """<script id="$id" type="application/json">${remoteDashboardJson.encodeToString(value).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")}</script>"""

private fun overviewScript(): String = """
<script>
(()=>{const q=s=>document.querySelector(s),a=s=>Array.from(document.querySelectorAll(s)),snap=()=>JSON.parse(q('#overview-snapshot').textContent);
const state=q('[data-live-state]'),announcer=q('[data-update-announcer]');const live=t=>{state.classList.add('connected');state.lastElementChild.textContent=t||'Live updates connected';};
const filter=()=>{const text=q('[data-filter-search]').value.toLowerCase(),status=q('[data-filter-status]').value,type=q('[data-filter-type]').value;a('[data-run-row]').forEach(row=>row.hidden=!!((text&&!row.dataset.search.includes(text))||(status&&row.dataset.status!==status)||(type&&row.dataset.type!==type)));};a('[data-filter-search],[data-filter-status],[data-filter-type]').forEach(el=>el.addEventListener('input',filter));
const type=q('[data-run-type]'),benchFields=a('.benchmark-only'),runFields=a('.run-only');const switchType=()=>{const benchmark=type.value==='benchmark';benchFields.forEach(el=>el.hidden=!benchmark);runFields.forEach(el=>el.hidden=benchmark);};type.addEventListener('change',switchType);switchType();
const node=(tag,text)=>{const el=document.createElement(tag);if(text!=null)el.textContent=String(text);return el;},cell=(row,text,cls)=>{const el=node('td',text);if(cls)el.className=cls;row.append(el);return el;};
const chart=(selector,legend,values,label)=>{const host=q(selector),key=String(label);host.replaceChildren();const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 100 100');svg.setAttribute('role','img');svg.setAttribute('aria-label',key);const total=Math.max(1,values.reduce((sum,item)=>sum+item.value,0));let offset=0;const colors=['#4de3d2','#78a6ff','#ae8cff','#ffca70','#ff8295'];values.forEach((item,index)=>{const circle=document.createElementNS('http://www.w3.org/2000/svg','circle'),length=item.value*100/total;circle.setAttribute('cx','50');circle.setAttribute('cy','50');circle.setAttribute('r','36');circle.setAttribute('fill','none');circle.setAttribute('stroke',colors[index%colors.length]);circle.setAttribute('stroke-width','14');circle.setAttribute('pathLength','100');circle.setAttribute('stroke-dasharray',length+' '+(100-length));circle.setAttribute('stroke-dashoffset',String(-offset));circle.setAttribute('transform','rotate(-90 50 50)');circle.append(node('title',item.label+': '+item.value));svg.append(circle);offset+=length;});const value=document.createElementNS('http://www.w3.org/2000/svg','text');value.setAttribute('x','50');value.setAttribute('y','52');value.setAttribute('text-anchor','middle');value.setAttribute('fill','#edf3ff');value.setAttribute('font-size','18');value.textContent=String(values.reduce((sum,item)=>sum+item.value,0));svg.append(value);host.append(svg);const list=q(legend);list.replaceChildren();values.forEach((item,index)=>{const entry=node('span'),dot=node('i');dot.style.background=colors[index%colors.length];entry.append(dot,document.createTextNode(item.label+' '+item.value));list.append(entry);});};
const rows=runs=>{const body=q('[data-runs-table]');body.replaceChildren();if(!runs.length){const row=node('tr'),empty=cell(row,'No runs have been recorded. Dispatch the first workload above.','muted');empty.colSpan=8;body.append(row);return;}runs.forEach(run=>{const row=node('tr');row.dataset.runRow='';row.dataset.status=run.status;row.dataset.type=run.type;row.dataset.search=[run.id,run.type,run.backend,run.runner,run.benchmark].filter(Boolean).join(' ').toLowerCase();const runCell=cell(row);const name=node('span',run.id);name.className='run-name';runCell.append(name,node('span',run.latestActivityAt||run.startedAt));runCell.lastElementChild.className='subtle';const stateCell=cell(row),badge=node('span',run.status);badge.className='status '+run.status.toLowerCase();stateCell.append(badge);const phase=node('span',run.phase);phase.className='subtle';stateCell.append(phase);const execution=cell(row,run.type);const sub=node('span',[run.backend,run.runner].filter(Boolean).join(' · ')||'Not recorded');sub.className='subtle';execution.append(sub);const progress=cell(row);if(run.progressPercent==null)progress.textContent='—';else{const bar=node('div'),fill=node('i'),note=node('span',run.progressPercent.toFixed(1)+'%');bar.className='mini-progress';fill.style.width=Math.max(0,Math.min(100,run.progressPercent))+'%';note.className='subtle';bar.append(fill);progress.append(bar,note);}cell(row,run.elapsedMillis==null?'—':Math.round(run.elapsedMillis/1000)+'s');cell(row,run.artifactCount+(run.artifactInventoryTruncated?'+':'')+' · '+run.outputBytes+' B');cell(row,run.primaryScore==null?'—':(run.primaryScore*100).toFixed(1)+'%');const action=cell(row),link=node('a','Inspect');link.href='/runs/'+encodeURIComponent(run.id);action.append(link);body.append(row);});filter();};
const update=data=>{q('[data-last-update]').textContent=data.generatedAt;const stats=data.stats, values={'Total runs':[stats.totalRuns,'Recorded fleet executions'],'Active now':[stats.activeRuns,'Queued or currently running'],'Successful':[stats.succeededRuns,stats.successRate==null?'Unavailable':(stats.successRate*100).toFixed(1)+'%'],'Failed':[stats.failedRuns,'Terminal failures'],'Runtime':[Math.round(stats.completedDurationMillis/1000)+'s','Completed work'],'Throughput':[stats.completedRunsPerRuntimeHour==null?'Unavailable':stats.completedRunsPerRuntimeHour.toFixed(2)+' runs/runtime h','Completed runs per aggregate runtime hour'],'Output':[stats.outputBytes+' B',stats.artifactCount+(stats.artifactTotalsPartial?'+':'')+' artifacts']};Object.entries(values).forEach(([label,value])=>{const card=q('[data-kpi="'+label+'"]');if(card){card.querySelector('b').textContent=String(value[0]);card.querySelectorAll('small')[1].textContent=value[1];}});chart('[data-status-chart]','[data-status-legend]',data.statusDistribution,'Status distribution');chart('[data-type-chart]','[data-type-legend]',data.typeDistribution,'Run type distribution');rows(data.runs);live('Live · updated '+data.generatedAt);if(announcer)announcer.textContent='Fleet updated. '+stats.activeRuns+' active, '+stats.failedRuns+' failed.';};
const fallback=()=>fetch('/api/overview',{credentials:'same-origin'}).then(r=>r.ok?r.json():Promise.reject()).then(update).catch(()=>{state.lastElementChild.textContent='Live feed unavailable';});
try{const source=new EventSource('/api/events');source.addEventListener('overview',event=>update(JSON.parse(event.data)));source.onopen=()=>live();source.onerror=()=>{state.classList.remove('connected');state.lastElementChild.textContent='Reconnecting live feed…';};}catch(_){fallback();}setInterval(fallback,30000);
})();
</script>
"""

private fun detailScript(id: String): String = """
<script>
(()=>{const q=s=>document.querySelector(s),a=s=>Array.from(document.querySelectorAll(s)),state=q('[data-live-state]'),announcer=q('[data-update-announcer]'),runId=${jsString(id)};
const live=t=>{state.classList.add('connected');state.lastElementChild.textContent=t||'Live updates connected';};const node=(tag,text)=>{const el=document.createElement(tag);if(text!=null)el.textContent=String(text);return el;},format=v=>v==null?'Unavailable':Number(v).toFixed(2),ratio=v=>v==null?'Unavailable':(Number(v)*100).toFixed(1)+'%';
const metrics=items=>{const host=q('[data-metrics]'),groups=new Map();items.forEach(item=>{const list=groups.get(item.category)||[];list.push(item);groups.set(item.category,list);});const grid=node('div');grid.className='metric-groups';groups.forEach((items,category)=>{const group=node('section'),heading=node('h3',category);group.className='metric-group';group.append(heading);items.forEach(item=>{const metric=node('div');metric.className='metric'+(item.numericValue==null?' unavailable':'');const label=node('small',item.label),value=node('b',item.numericValue==null?'Unavailable':(item.unit==='ratio'?ratio(item.numericValue):format(item.numericValue)+(item.unit?' '+item.unit:''))),note=node('small',item.numericValue==null?(item.availability==='INVALID'?'Invalid value rejected':item.availability+': not emitted'):(item.provenance||item.availability));label.title=item.description;metric.append(label,value,note);group.append(metric);});grid.append(group);});host.replaceChildren(grid);};
const activity=series=>{const host=q('[data-activity-chart]'),grid=node('div');grid.className='series-grid';series.forEach(item=>{const card=node('section'),title=node('h3',item.label);card.className='series-card'+(item.points.length?'':' unavailable');card.append(title);if(!item.points.length){card.append(node('p','Not emitted'),node('small',item.unit));grid.append(card);return;}const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 100 100');svg.setAttribute('role','img');svg.setAttribute('aria-label',item.label+' in '+item.unit);const max=Math.max(1,...item.points.map(point=>point.value)),line=document.createElementNS('http://www.w3.org/2000/svg','polyline');line.setAttribute('fill','none');line.setAttribute('stroke','#4de3d2');line.setAttribute('stroke-width','2');line.setAttribute('points',item.points.map((point,i)=>((i*100/Math.max(1,item.points.length-1))+','+(92-point.value*82/max))).join(' '));svg.append(line);card.append(svg,node('small',format(item.points[item.points.length-1].value)+' '+item.unit+' latest'));grid.append(card);});host.replaceChildren(grid);};
const events=items=>{const host=q('[data-events]');host.replaceChildren();if(!items.length){host.append(node('li','No lifecycle events emitted yet.'));return;}items.forEach(item=>{const entry=node('li'),time=node('time',item.timestamp||'Time unavailable'),title=node('strong',item.title),detail=node('span',item.detail);entry.append(time,title,detail);host.append(entry);});};
const benchmark=(value,comparison)=>{const host=q('[data-benchmark]');host.replaceChildren();if(!value){const empty=node('p','No benchmark report was emitted for this run.');empty.className='muted';host.append(empty);}else{const facts=node('dl');facts.className='compact-facts';[['Benchmark',value.benchmark||'—'],['Accuracy',ratio(value.accuracy)],['Correct / total',[value.correct,value.total].filter(v=>v!=null).join(' / ')||'Unavailable']].forEach(([label,text])=>{const item=node('div'),term=node('dt',label),definition=node('dd',text);item.append(term,definition);facts.append(item);});host.append(facts);const table=node('table'),head=node('thead'),headRow=node('tr');['Task','Score','Latency','Result'].forEach(label=>headRow.append(node('th',label)));head.append(headRow);const body=node('tbody');value.tasks.forEach(task=>{const row=node('tr');[task.taskId,task.score==null?'—':format(task.score),task.latencyMillis==null?'—':Math.round(task.latencyMillis/1000)+'s',task.error||(task.correct==null?'Unavailable':task.correct?'Correct':'Incorrect')].forEach(text=>row.append(node('td',text)));body.append(row);});table.append(head,body);const wrap=node('div');wrap.className='table-scroll';wrap.append(table);host.append(wrap);}const section=node('section'),heading=node('h3','Matched comparison');section.className='comparison';if(!comparison){section.append(heading,node('p','No matched baseline comparison was emitted.'));}else{heading.append(node('small',' baseline '+comparison.baselineArm));section.append(heading);const cues=node('ul');cues.className='comparison-cues';comparison.comparisons.forEach(cue=>{const entry=node('li'),title=node('strong',cue.comparatorArm),badge=node('span',cue.interpretation.replaceAll('_',' ').toLowerCase()),ci=cue.lower95==null?'':' · 95% CI '+format(cue.lower95)+' to '+format(cue.upper95),note=node('small',cue.meanDifference==null?'No paired quality estimate':format(cue.meanDifference)+' quality delta'+ci);badge.className='status';entry.append(title,badge,note);cues.append(entry);});const table=node('table'),head=node('thead'),headRow=node('tr');['Arm','Quality','Cost','Latency','Success'].forEach(label=>headRow.append(node('th',label)));head.append(headRow);const body=node('tbody');comparison.arms.forEach(arm=>{const row=node('tr');[arm.arm,arm.meanQuality==null?'—':format(arm.meanQuality),arm.meanCostUsd==null?'—':format(arm.meanCostUsd)+' USD',arm.meanLatencyMillis==null?'—':Math.round(arm.meanLatencyMillis/1000)+'s',(arm.successCount||0)+'/'+(arm.cellCount||0)].forEach(text=>row.append(node('td',text)));body.append(row);});table.append(head,body);const wrap=node('div');wrap.className='table-scroll';wrap.append(table);section.append(cues,wrap);}host.append(section);};
const artifacts=(items,kinds)=>{const mix=q('[data-artifact-mix]');mix.replaceChildren();(kinds||[]).forEach((kind,index)=>{const entry=node('span'),dot=node('i');dot.style.background=['#4de3d2','#78a6ff','#ae8cff','#ffca70','#ff8295'][index%5];entry.append(dot,document.createTextNode(kind.label+' '+kind.value));mix.append(entry);});const host=q('[data-artifacts]');host.replaceChildren();if(!items.length){const row=node('tr'),empty=node('td','This run has not written safe artifacts yet.');empty.colSpan=5;empty.className='muted';row.append(empty);host.append(row);return;}items.forEach(item=>{const row=node('tr');[item.relativePath,item.kind,item.sizeBytes+' B',item.modifiedAt].forEach(text=>row.append(node('td',text)));const actions=node('td');actions.className='artifact-actions';if(item.previewable){const button=node('button','Preview');button.type='button';button.className='text-link preview-button';button.dataset.previewPath=item.relativePath;actions.append(button);}if(item.downloadable){const download=node('a','Download');download.href='/outputs/'+encodeURIComponent(runId)+'/'+item.relativePath.split('/').map(encodeURIComponent).join('/');download.download='';actions.append(download);}else{const restricted=node('span','Restricted');restricted.className='subtle';actions.append(restricted);}row.append(actions);host.append(row);});};
const warnings=items=>{const host=q('[data-warnings]');host.replaceChildren();items.forEach(item=>{const warning=node('p','Warning: '+item);warning.className='notice danger';host.append(warning);});};
const update=data=>{q('[data-last-update]').textContent=data.generatedAt;const status=q('[data-run-status]');status.textContent=data.summary.status;status.className='status '+data.summary.status.toLowerCase();a('[data-run-phase]').forEach(e=>e.textContent=data.summary.phase);q('[data-run-progress]').textContent=data.summary.progressPercent==null?'—':data.summary.progressPercent.toFixed(1)+'%';q('[data-progress-bar]').style.width=Math.max(0,Math.min(100,data.summary.progressPercent||0))+'%';q('[data-run-elapsed]').textContent=data.summary.elapsedMillis==null?'—':Math.round(data.summary.elapsedMillis/1000)+'s';q('[data-log]').textContent=(data.log.lines||[]).join('\n')||'No safe structural log output has been written yet.';q('[data-log-note]').textContent=data.log.sizeBytes+' B log · '+(data.log.truncated?'tail bounded · ':'')+(data.log.withheldLines?data.log.withheldLines+' unstructured line(s) withheld for privacy.':'only structural lifecycle lines shown.');metrics(data.metrics);activity(data.series);events(data.events);benchmark(data.benchmark,data.comparison);artifacts(data.artifacts,data.artifactKinds);warnings(data.warnings);live('Live · updated '+data.generatedAt);if(announcer)announcer.textContent='Run '+data.summary.status+'. Phase '+data.summary.phase+'.';};
const preview=path=>fetch('/api/runs/'+encodeURIComponent(runId)+'/preview?'+new URLSearchParams({path}).toString(),{credentials:'same-origin'}).then(r=>r.ok?r.json():Promise.reject()).then(data=>{const el=q('[data-preview]');el.hidden=false;el.textContent=data.content+(data.truncated?'\n\n[Preview truncated]':'');}).catch(()=>{const el=q('[data-preview]');el.hidden=false;el.textContent='Preview is unavailable for this artifact.';});
document.addEventListener('click',e=>{const button=e.target.closest('.preview-button');if(button)preview(button.dataset.previewPath);});const fallback=()=>fetch('/api/runs/'+encodeURIComponent(runId),{credentials:'same-origin'}).then(r=>r.ok?r.json():Promise.reject()).then(update).catch(()=>{state.lastElementChild.textContent='Live feed unavailable';});
try{const source=new EventSource('/api/runs/'+encodeURIComponent(runId)+'/events');source.addEventListener('run',event=>update(JSON.parse(event.data)));source.onopen=()=>live();source.onerror=()=>{state.classList.remove('connected');state.lastElementChild.textContent='Reconnecting live feed…';};}catch(_){fallback();}setInterval(fallback,30000);
})();
</script>
"""

private fun html(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
private fun url(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
private fun urlPath(value: String) = value.split('/').joinToString("/") { url(it) }
private fun jsString(value: String) = "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'"
private fun percent(value: Double?) = value?.let { "${"%.1f".format(it)}%" } ?: "Unavailable"
private fun ratioPercent(value: Double?) = value?.let { "${"%.1f".format(it * 100)}%" } ?: "Unavailable"
private fun duration(milliseconds: Long?) = milliseconds?.let { if (it < 60_000) "${it / 1000}s" else if (it < 3_600_000) "${it / 60_000}m" else "${it / 3_600_000}h ${it % 3_600_000 / 60_000}m" } ?: "—"
private fun bytes(value: Long) = when { value < 1024 -> "$value B"; value < 1024 * 1024 -> "${value / 1024} KiB"; else -> "${"%.1f".format(value / (1024.0 * 1024.0))} MiB" }
private fun number(value: Double, unit: String?) = "${"%.2f".format(value)}${unit?.let { " $it" }.orEmpty()}"
