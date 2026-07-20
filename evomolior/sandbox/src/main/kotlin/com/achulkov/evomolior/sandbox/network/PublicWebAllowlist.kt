package com.achulkov.evomolior.sandbox.network

/**
 * Shared baseline host allowlist for general-purpose public web access.
 *
 * Keep this intentionally broad enough for ordinary runs, but limited to obvious public
 * infrastructure so the sandbox still provides meaningful egress control.
 */
val PUBLIC_WEB_HOST_ALLOWLIST: Set<String> = setOf(
    "openrouter.ai",
    "openai.com",
    "anthropic.com",
    "github.com",
    "githubusercontent.com",
    "gitlab.com",
    "bitbucket.org",
    "sourceforge.net",
    "wikipedia.org",
    "wikimedia.org",
    "arxiv.org",
    "news.ycombinator.com",
    "stackoverflow.com",
    "developer.mozilla.org",
    "docs.python.org",
    "docs.rs",
    "kotlinlang.org",
    "jetbrains.com",
    "docs.github.com",
    "npmjs.com",
    "registry.npmjs.org",
    "crates.io",
    "search.maven.org",
    "repo.maven.apache.org",
    "repo1.maven.org",
    "pypi.org",
    "files.pythonhosted.org",
    "google.com",
    "googleapis.com",
    "googleusercontent.com",
    "storage.googleapis.com",
    "docs.google.com",
    "huggingface.co",
    "cloudflare.com",
    "medium.com",
    "towardsdatascience.com",
    "reddit.com",
    "substack.com",
    "substackcdn.com",
    "dev.to"
)
