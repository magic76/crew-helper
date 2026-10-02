# App Capability Registry

Crew Helper can execute declarative low-risk App fast paths without shipping a
new APK for every supported App.

## Runtime priority

1. Local user capability
2. Synced remote capability
3. Bounded standard Android intent probe
4. Existing UI Agent (Accessibility / Vision)

Capabilities never grant authorization. Sensitive commits still use the normal
Runtime policy.

## Remote manifest

The URL configured in **App 經驗 → Sync Capability Registry** must be HTTPS.

```json
{
  "version": 1,
  "revision": "2026-10-02.1",
  "apps": [
    {
      "package": "com.example.music",
      "label": "Example Music",
      "capabilities": [
        {
          "id": "OPEN_ALBUM",
          "label": "Open album",
          "kind": "URI",
          "uriTemplate": "examplemusic://album/{albumId}"
        },
        {
          "id": "SEARCH",
          "label": "Open search",
          "kind": "URI",
          "uriTemplate": "examplemusic://search?query={query}"
        }
      ]
    }
  ]
}
```

Capability IDs are uppercase `A-Z0-9_`. Parameters are discovered from
`{name}` placeholders and URL encoded by Runtime.

For a whole public URL, use exactly `{url}`; Runtime validates its URI scheme
before dispatch.

## Allowed execution

Runtime supports declarative URI / Intent dispatch with these low-risk Android
actions:

- `android.intent.action.VIEW` (default)
- `android.intent.action.SENDTO`
- `android.intent.action.DIAL`

The target package is always explicit, `resolveActivity()` must succeed, and
Crew observes the post-action App/screen before treating the step as evidence.

Blocked URI schemes include `javascript:`, `file:`, `content:`,
`intent:`, and `data:`.

No JavaScript, Java/Kotlin, shell commands, reflection, downloadable modules, or
arbitrary executable code are supported by the registry.

## Local override

A locally configured capability with the same `package + id` replaces the
synced definition. This lets one device patch an App-version-specific deep link
without changing the shared registry.
