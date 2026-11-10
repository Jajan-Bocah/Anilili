# Remote Provider Configurations

This directory contains example remote provider configurations for the Anilili Hybrid Remote Provider Engine.

## Configuration Schema

Each configuration follows the schema defined in `RemoteProviderConfig.kt`:

- `schemaVersion`: Must be 1 (current version)
- `providers`: Array of provider definitions
- `metadata`: Config metadata (name, version, author, source URL)
- `security`: Security policy (allowed domains, rate limits, timeouts)
- `update`: Update settings (auto/manual, interval)
- `workflows`: Declarative workflows for search, episodes, sources, servers

## Provider Workflow Steps

Supported step types:
- `http`: Make HTTP requests with templated URLs, headers, query params
- `parse_json`: Parse JSON and extract with JSONPath
- `parse_html`: Parse HTML (requires Jsoup)
- `select`: CSS selector extraction from HTML
- `regex`: Regex pattern matching
- `map`: Field mapping/renaming
- `normalize`: Text normalization (trim, lowercase, URL resolve, episode number, language)
- `assert`: Validation assertions
- `filter`: Filter arrays

## Template Variables

Use `{{variable}}` syntax in step parameters:
- Input variables from previous steps or initial context
- Nested access: `{{response.body.data}}`
- Array access: `{{items[0].name}}`

## Security

- All URLs must use HTTPS
- Domain allowlist enforced
- Private IP ranges blocked (10.x, 192.168.x, 172.16-31.x, 127.x, 169.254.x)
- Response size limits enforced
- Request rate limits per workflow run
- Redirect validation

## Example Configs

- `anichin.json` - Anichin (Indonesian anime provider)
- `kuramanime.json` - Kuramanime (Indonesian anime provider)

## Adding New Providers

1. Create a JSON config following the schema
2. Host it at an HTTPS URL
3. Add via Settings → Servers → Custom sources
4. The app will validate, fetch, and register the providers

## Development Notes

These are TEMPLATE configurations with placeholder domains (`example.com`). 
To use them, replace the domains with actual working endpoints and test the workflows.

Per the roadmap, actual provider configs must be created by:
1. Auditing actual HTML/JS/network requests
2. Capturing sanitized fixtures
3. Not inventing selectors/endpoints/URLs