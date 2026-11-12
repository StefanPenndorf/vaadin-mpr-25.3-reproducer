# MPR: legacy client stalls after an early Flow navigation (regression in Flow 25.3)

Minimal Spring Boot application that reproduces a regression with the
[Multiplatform Runtime (MPR)](https://vaadin.com/docs/latest/tools/mpr) when upgrading from Vaadin 25.2 to 25.3.

**Symptom:** The page stays blank (only the loading bar keeps running). The legacy (Vaadin 8) client never
processes another server response. Nothing is logged to the browser console.

| Branch        | Vaadin platform | Flow    | MPR   | Vaadin 8 | Spring Boot | Result                   |
|---------------|-----------------|---------|-------|----------|-------------|--------------------------|
| `vaadin-25.2` | 25.2.14         | 25.2.17 | 8.0.1 | 8.32.3   | 4.1.2       | works                    |
| `main`        | 25.3.5          | 25.3.7  | 8.0.1 | 8.32.3   | 4.1.2       | **legacy client stalls** |

The only difference between the two branches is the Vaadin platform version (see the last commit on `main`).

## Run

Requirements: Java 21, Maven 3.9+, a Vaadin license that covers MPR and Vaadin 8 extended maintenance.

```bash
mvn spring-boot:run
```

Open <http://localhost:8080/>. The browser ends up on `/migrated`.

- Branch `vaadin-25.2`: after a few seconds, the text *"Legacy content rendered - the legacy client is working."* appears. The browser console shows the swallowed error `(TypeError) : Cannot read properties of undefined (reading 'b')`.
- Branch `main` (25.3): the page stays blank, the loading bar keeps running. This happens on every page load.

The application delays every request for a legacy widget set file by 3 seconds (`WidgetsetDelayFilter`,
`repro.widgetset-delay-ms`). This simulates downloading the widget set over a slow network with a cold browser
cache and makes the reproduction deterministic. With `repro.widgetset-delay-ms=0`, the same effect can be achieved
with Chrome DevTools ("Disable cache" plus network throttling "Fast 4G"), but then it is timing dependent.

## What the application does

1. `LegacyNavigatorRoute` (`@Route("")`, extends `MprNavigatorRoute`) hosts the legacy `Navigator`. It is the only
   legacy wrapper on the page.
2. While MPR bootstraps the legacy UI (`/framework` request), the legacy `Navigator` enters its initial view
   `LegacyStartView`. That view has already been migrated to the Flow route `MigratedRoute`
   (`MprRouteAdapter<MigratedLegacyView>`), so `enter()` schedules `UI.navigate(MigratedRoute.class)` with
   `UI.beforeClientResponse(...)`.
3. The Flow navigation reaches the browser via push and detaches the wrapper of `LegacyNavigatorRoute` **before**
   the legacy widget set has started.
4. When the legacy client then processes its first message, it tries to attach the legacy content to the wrapper
   that no longer exists.

## Root cause

`AbstractMprUIContentConnector.attachWidget()` (mpr-core) calls
`window.Vaadin.Flow.clients[appId].getByNodeId(nodeId)`, gets `null` for the detached node, and then calls
`window.Vaadin.Flow.clients[appId].addDomBindingListener(nodeId, ...)`. For an unregistered node, the Flow client
throws a `TypeError` in both versions (`StateTree.getNode()` returns `null`).

- **Flow 25.2** published `addDomBindingListener` wrapped in GWT's `$entry`, with an uncaught exception handler
  registered. The exception is caught, reported to the console, and the call returns normally. MPR continues,
  calls `handleResponseMessage()`, defines `setResponse`, and the page loads.
- **Flow 25.3** publishes the TypeScript port of the client API (`internal/client/publishClient.js`) without an
  `$entry` equivalent ([vaadin/flow#24952](https://github.com/vaadin/flow/pull/24952), activated by
  [vaadin/flow#24953](https://github.com/vaadin/flow/pull/24953)). The `TypeError` now propagates into MPR, where
  the Vaadin 8 `MessageHandler.sendHierarchyChangeEvents()` swallows it. `handleResponseMessage()` is never called,
  `setResponse` is never defined, and every later legacy response piles up in
  `window.Vaadin.Flow.pendingResponses`.

With more than one legacy wrapper on the page (for example a `LegacyWrapper` in a `RouterLayout`), the outcome on
25.3 becomes random: MPR iterates the wrappers from a `HashMap` keyed by components with identity hash codes. The
page only recovers if a still existing wrapper happens to be processed before the detached one.

## Diagnosis in the browser

Run this in the console of the stalled page:

```js
({
  setResponse: typeof document.querySelector('.v-ui')?.setResponse,
  pendingResponses: Object.fromEntries(Object.entries(window.Vaadin.Flow.pendingResponses || {}).map(([k, v]) => [k, v.length])),
  legacyClientActive: Object.values(window.vaadin.clients).map(c => c.isActive())
})
```

On 25.3 the result is `setResponse: "undefined"`, a non-empty `pendingResponses` and `legacyClientActive: [true]`.
