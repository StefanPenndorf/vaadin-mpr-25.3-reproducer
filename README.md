# MPR: legacy client stalls after an early Flow navigation (regression in Flow 25.3)

Minimal Spring Boot application that reproduces a regression with the
[Multiplatform Runtime (MPR)](https://vaadin.com/docs/latest/tools/mpr) when upgrading from Vaadin 25.2 to 25.3.

**Symptom:** The page stays blank (only the loading bar keeps running). The legacy (Vaadin 8) client never
processes another server response. Nothing is logged to the browser console.

| Branch        | Vaadin platform | Flow    | MPR   | Vaadin 8 | Spring Boot | Result                                  |
|---------------|-----------------|---------|-------|----------|-------------|-----------------------------------------|
| `vaadin-25.2` | 25.2.14         | 25.2.17 | 8.0.1 | 8.32.3   | 4.1.2       | works                                   |
| `main`        | 25.3.5          | 25.3.7  | 8.0.1 | 8.32.3   | 4.1.2       | **legacy client stalls**                |
| `workaround`  | 25.3.5          | 25.3.7  | 8.0.1 | 8.32.3   | 4.1.2       | works, navigation delayed (see below)   |

The only difference between `vaadin-25.2` and `main` is the Vaadin platform version
([compare](https://github.com/StefanPenndorf/vaadin-mpr-25.3-reproducer/compare/vaadin-25.2...main)).
`workaround` is `main` plus the application-side workaround
([compare](https://github.com/StefanPenndorf/vaadin-mpr-25.3-reproducer/compare/main...workaround)).

## Run

Requirements: Java 21, Maven 3.9+, a Vaadin license that covers MPR and Vaadin 8 extended maintenance.

```bash
mvn spring-boot:run
```

Open <http://localhost:8080/>. The browser ends up on `/migrated`.

- Branch `vaadin-25.2`: after a few seconds, the text *"Legacy content rendered - the legacy client is working."* appears. The browser console shows the swallowed error `(TypeError) : Cannot read properties of undefined (reading 'b')`.
- Branch `main` (25.3): the page stays blank, the loading bar keeps running. This happens on every page load.
- Branch `workaround` (25.3): the legacy content appears, a bit later than on 25.2.

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

The navigation is deliberately not done with the legacy `Navigator` (`navigateTo("migrated")`), because
`MprNavigator.navigateTo()` calls `UI.navigate()` synchronously inside the legacy bootstrap request. The wrapper
would then already be gone when the first legacy message is written, and the race could not occur.

## Root cause

`AbstractMprUIContentConnector.attachWidget()` (mpr-core) calls
`window.Vaadin.Flow.clients[appId].getByNodeId(nodeId)`, gets `null` for the detached node, and then calls
`window.Vaadin.Flow.clients[appId].addDomBindingListener(nodeId, ...)`. For an unregistered node, the Flow client
throws a `TypeError` in both versions (`StateTree.getNode()` returns `null`).

- **Flow 25.2** published `addDomBindingListener` wrapped in GWT's `$entry`, with an uncaught exception handler
  registered. The exception is caught, reported to the console, and the call returns normally. MPR continues,
  calls `handleResponseMessage()`, defines `setResponse`, and the page loads.
- **Flow 25.3** publishes the TypeScript port of the client API (`internal/client/publishClient.ts`) without an
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

## Workaround (branch `workaround`)

`LegacyStartView` still schedules the navigation with `UI.beforeClientResponse(...)`, but first runs a script in
the browser that resolves once every legacy client reports `isActive() == false` (polled every 50 ms, given up
after 10 s). Only then does the server call `UI.navigate(...)`. The legacy client has attached its widgets and
defined `setResponse` by that time, so detaching the wrapper no longer matters.

This relies on undocumented client internals (`window.vaadin.clients`, `isActive()`), on polling and on an arbitrary
timeout, so it is not a real fix.

# Detailed analysis

The issue first appeared in a larger application. The following analysis was done there and then confirmed with
this reproducer. Sources examined: mpr-core 8.0.1 (client sources and server bytecode),
vaadin-server-mpr-jakarta / vaadin-client 8.32.3, and flow-client 25.2.13 / 25.3.7.

## Code walkthrough

1. **MPR server side: delivering legacy responses.** `com.vaadin.mpr.core.AbstractLegacyEmbed#writeResponse()`
   sends every legacy response to the browser with `Page.executeJs`. If `setResponse` is already defined on the
   legacy UI element, the script calls it. Otherwise it appends the response to
   `window.Vaadin.Flow.pendingResponses[appId]`. This buffering was introduced for
   [vaadin/multiplatform-runtime#86](https://github.com/vaadin/multiplatform-runtime/issues/86).
2. **MPR client side: attaching legacy widgets.** `AbstractMprUIContentConnector#onConnectorHierarchyChange()` calls
   `attachWidget(widget, appId, nodeId)` for each child connector. `attachWidget()` does the following:
   - It looks up the wrapper element with `Vaadin.Flow.clients[appId].getByNodeId(nodeId)`.
   - If that returns `null`, it registers `Vaadin.Flow.clients[appId].addDomBindingListener(nodeId, …)` to attach
     the widget later.
   - Only after that does it call `handleResponseMessage()`. This is the only place that defines `setResponse`,
     either directly or in a deferred command that also drains `pendingResponses`.

   MPR treats `null` from `getByNodeId()` as "not bound yet". It cannot tell that apart from "no longer exists".
3. **Flow client, same in both versions.** `addDomBindingListener()` calls `ApplicationConnection.addDomSetListener()`,
   which runs `getStateTree().getNode(nodeId).addDomNodeSetListener(…)`. `StateTree.getNode()` returns `null` for an
   unregistered node, so this throws in 25.2 (GWT, `com.vaadin.client.ApplicationConnection`) as well as in 25.3
   (`internal/client/ApplicationConnection.ts`, which deliberately mirrors the Java non-null dereference).
4. **Flow client, different between the versions: error handling of the published API.**
   - In 25.2, `ApplicationConnection#publishJavascriptMethods()` wraps each published function in `$entry(...)`. The
     constructor also registers `GWT.setUncaughtExceptionHandler(registry.getSystemErrorHandler()::handleError)`.
     With such a handler registered, `$entry` catches the exception, reports it (console:
     `(TypeError) : Cannot read properties of undefined (reading 'b')`), and returns normally.
   - In 25.3, `internal/client/publishClient.ts` publishes plain arrow functions. Its header comment notes that the
     port has no `$entry` equivalent, so the exception reaches the caller. The global `error` listener installed by
     `ApplicationConnection.create()` never sees it, because the Vaadin 8 client catches it first (point 5).
   - The change came with vaadin/flow#24952 and became active with vaadin/flow#24953, both part of Flow 25.3.0.
5. **Vaadin 8 client.** `com.vaadin.client.communication.MessageHandler#sendHierarchyChangeEvents()` catches
   `Throwable` per event and only logs `"Error sending hierarchy change events"` via `java.util.logging`. Nothing
   appears in the browser console.
6. **Result on 25.3.** The exception aborts the loop in `onConnectorHierarchyChange()`. If the detached wrapper is
   processed before any existing one, `handleResponseMessage()` is never reached. `setResponse` stays undefined,
   all legacy responses stay in `pendingResponses`, and `isActive()` of the legacy client stays `true`.
7. **Order of the wrappers.** `com.vaadin.mpr.core.AbstractMprUIContent#iterator()` iterates
   `getComponentSettings().keySet()`. That is a `java.util.HashMap<Connector, ComponentSettings>`
   (`MprUIContentState.componentSettings`), and `com.vaadin.server.AbstractClientConnector#hashCode()` delegates to
   `Object.hashCode()`. The order of the children in the first legacy message therefore depends on identity hash
   codes and varies between runs. With a single wrapper, as in this reproducer, the detached one is always first.

## Observations in the original application

**Structure:** The application has two legacy wrappers.
- A `LegacyWrapper` with header and menu sits in the `RouterLayout`.
- An `MprNavigatorRoute` hosts the legacy `Navigator`.

Migrated views are `MprRouteAdapter` routes. The legacy `Navigator` forwards to them with `UI.navigate()`,
scheduled via `UI.beforeClientResponse()`. After login, the browser loads the `MprNavigatorRoute`, and the legacy
navigator's initial navigation targets a migrated route. The Flow navigation then detaches the `MprNavigatorRoute`
(Flow node 4), while the `RouterLayout` with the header wrapper (node 6) remains.

**Symptoms:** blank page, endless loading bar, no console error, no failed HTTP request. In the stalled state,
`pendingResponses` holds at least one legacy response and the legacy client's `isActive()` stays `true`. A reload
or a second login usually helps, because the browser cache is then warm.

### Deterministic local runs

**Setup:** A local HTTP proxy delayed every response under `/framework/VAADIN/widgetsets/` by 4 s and made it
non-cacheable. After the Flow client had started, `getByNodeId` and `addDomBindingListener` were wrapped with
logging, and `pendingResponses` and `setResponse` were polled every 20 ms. The `/framework` response, which carries
the first legacy message, was captured.

**Result:** In every run, the navigation had detached node 4 before the widget set started.

| Run | Flow    | Scenario                                                                                       | First wrapper in the first legacy message | Result |
|-----|---------|------------------------------------------------------------------------------------------------|-------------------------------------------|--------|
| 1   | 25.3.7  | fresh session                                                                                  | header (node 6)                           | recovers |
| 2   | 25.3.7  | reload in the same session                                                                     | header                                    | recovers: `addDomBindingListener(4)` throws, but `setResponse` was defined before |
| 3   | 25.3.7  | as run 2, push WebSocket held back until after `/framework`, so a legacy response is buffered  | header                                    | recovers, buffered response drained |
| 4   | 25.3.7  | fresh session, WebSocket held back as in run 3                                                 | `MprNavigatorRoute` (node 4)              | **stalls**: header never attached, `setResponse` undefined, `pendingResponses` 1 → 2, unchanged after 35 s |
| 5   | 25.3.7  | fresh session                                                                                  | node 4                                    | **stalls**, `pendingResponses` 0 → 1 |
| 6   | 25.2.13 | fresh session                                                                                  | node 4                                    | recovers: `addDomBindingListener(4)` returns normally, Flow logs the error, header attached |

Trace of run 5 (Flow 25.3.7, `performance.now()` in ms):

```
2995  node 4 = null, node 6 = DIV          (navigation done, widget set still loading)
9669  getByNodeId(4) -> null
9670  addDomBindingListener(4)
9671  addDomBindingListener(4) throws TypeError: Cannot read properties of null (reading 'addDomNodeSetListener')
9741  .v-ui present, setResponse = undefined
9796  pendingResponses: 1
```

Trace of run 6 (Flow 25.2.13):

```
 3951  node 4 = DIV, node 6 = DIV          (navigation not yet done)
10807  getByNodeId(4) -> null              (navigation done before the widget set started)
10808  addDomBindingListener(4)            (returns normally)
10809  getByNodeId(6) -> DIV
10902  setResponse = function
11073  getByNodeId(10) -> DIV              (wrapper of the migrated route)
console error: (TypeError) : Cannot read properties of undefined (reading 'b')
```

### Timing with browser throttling

Chrome with cache disabled and throttling "Fast 4G", Flow 25.3.7 (ms since page load):

```
13876  XHR ./framework – legacy bootstrap request starts
14061  ./framework response; on the server, the legacy Navigator has scheduled the Flow navigation
14869  push message (~1.8 kB) with the navigation arrives – widget set not loaded yet
14939  history.pushState(<migrated route>); MprNavigatorRoute (node 4) removed
15468  widget set iframe inserted (cache.js ~515 kB compressed, plus styles.css ~380 kB);
       the legacy client processes its first message
15523  addDomBindingListener(4) throws TypeError
```

Throttling slows down the large widget set download far more than the small push message, so the navigation
wins the race much more often. Without throttling and with a warm cache, the widget set usually wins.

## Possible fixes

- **Flow:** Keep the 25.2 contract of the functions published on `window.Vaadin.Flow.clients[appId]`: catch
  exceptions, report them to the system error handler, and return normally. At minimum, `addDomBindingListener()`
  should ignore unknown nodes.
- **MPR:** Skip wrappers that no longer exist in `attachWidget()`, and define `setResponse` and drain
  `pendingResponses` regardless of whether individual children could be attached.
