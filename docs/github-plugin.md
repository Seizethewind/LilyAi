# GitHub plugin

Ships in the app as `assets/plugins/github.json`. Like every plugin it arrives **off with nothing allowed**: turn it on, allow `http:api.github.com`, then fill in the token.

## Set up

1. On GitHub: Settings, Developer settings, Personal access tokens, Fine-grained tokens, Generate new token.
2. Pick only the repositories Lily may touch. Permissions: **Pull requests: Read and write**, **Checks: Read**. Nothing else is needed.
3. In Lily: Plugins, GitHub, paste the token into "GitHub token". It is stored encrypted and never shown again or given to Lily.

## Tools

| Tool | Does | Asks |
|---|---|---|
| `gh_list_prs` | newest 5 open pull requests | can be set to "don't ask again" |
| `gh_get_pr` | one pull request, including the head commit sha | can be set to "don't ask again" |
| `gh_pr_checks` | check results for a commit sha | can be set to "don't ask again" |
| `gh_create_pr` | opens a pull request from a pushed branch | every time |

There is no merge, close, delete or force tool, and the http layer has no DELETE, so a token with more rights than needed still cannot be used for those through this plugin.

## Limits

- Answers are cut at 16 KB. GitHub returns a lot of JSON per pull request, so `gh_list_prs` may show fewer than 5 and say so. Use `gh_get_pr` for one.
- `gh_pr_checks` needs the commit sha (from `gh_get_pr`), not a branch name: a branch with a slash would need an encoded slash, which the app refuses.
- Pull request titles and comments are written by other people. Every answer is marked as untrusted data for Lily.
- Not compiled or run when written. Test: `GithubPluginTest` (`./gradlew testDebugUnitTest`).
