# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

OpenEMS is a modular Energy Management System with three deployable parts in this one repo:

- **OpenEMS Edge** (`io.openems.edge.*`) — runs on-site (e.g. a Raspberry Pi/IoT gateway), talks to hardware (batteries, meters, PV inverters, EVSE, heat pumps, ...) and runs control algorithms.
- **OpenEMS Backend** (`io.openems.backend.*`) — cloud-side server aggregating data from many Edges.
- **OpenEMS UI** (`ui/`) — Angular web app, can connect either directly to an Edge or through the Backend.

Edge and Backend are Java 21 on an OSGi framework (Apache Felix), built with **bnd**/bndtools via Gradle. `io.openems.common` holds code shared between Edge and Backend (JSON-RPC definitions, helper utils). The UI is a separate Angular/Ionic/Capacitor project (own `package.json`, builds independently of Gradle).

Licensing differs by part: Edge/Backend are EPL-2.0, UI is AGPL-3.0.

## Build & test commands

All Gradle commands run from the repo root. Custom task groups are `OpenEMS-Build` and `OpenEMS-Test` (see `build.gradle`).

```bash
./gradlew build                     # compile + test everything (Java)
./gradlew checkstyleAll             # checkstyle for Edge + Backend (cnf/checkstyle.xml)
./gradlew checkstyleEdge            # checkstyle for Edge bundles only
./gradlew checkstyleBackend         # checkstyle for Backend bundles only
./gradlew testEdge                  # run JUnit tests for all Edge bundles
./gradlew testBackend               # run JUnit tests for all Backend bundles
./gradlew :io.openems.edge.io.shelly:test   # run tests for a single bundle
./gradlew :io.openems.edge.io.shelly:test --tests "*SomeTest*"  # single test class

./gradlew resolve                   # resolve/update *.bndrun files (all)
./gradlew buildEdge                 # fat jar -> build/openems-edge.jar
./gradlew buildBackend              # fat jar -> build/openems-backend.jar
./gradlew buildBackendEdge          # fat jar -> build/openems-backend-edge.jar (proxy app)
```

Running the fat jar: `java -Dfelix.cm.dir=/etc/openems/ -jar openems-osgi.jar` (Edge) or `openems-backend.jar` (Backend), then configure Components via the Apache Felix Web Console.

**`EdgeApp.bndrun` and `BackendApp.bndrun` are generated files.** They list resolved bundle versions and CI fails the build if `./gradlew resolve` produces a diff (`git diff --exit-code io.openems.edge.application/EdgeApp.bndrun`). If you add/remove a bundle dependency of the Edge/Backend app, run `./gradlew resolve` and commit the resulting `.bndrun` changes — don't hand-edit the `-runbundles` list.

UI (run from `ui/`):

```bash
npm ci
npm run lint                        # ng lint + i18n key lint
ng serve -o -c openems-edge-dev     # dev server against an Edge on :8085
ng serve -o -c openems-backend-dev  # dev server against a Backend on :8082
ng build -c "openems,openems-edge-prod,prod"      # production build, Edge-targeted
ng build -c "openems,openems-backend-prod,prod"   # production build, Backend-targeted
npm run test                        # ng test (Karma/Jasmine)
ng test -c "local"                  # test with Karma UI
```

`tools/prepare-commit.sh` is the traditional pre-PR sanity script: cleans up Eclipse project files, re-resolves all three `.bndrun` files, and rebuilds+tests the UI. Not required for every change, but useful before a PR touching bundle dependencies or the UI.

CI (`.github/workflows/build.yml`) runs, in this order for Java: checkstyle → `gradlew build` → `gradlew resolve` (must produce no diff) → JaCoCo coverage; for UI: `npm ci` → lint → `ng build` (edge-prod) → Karma tests headless.

## Architecture (Edge/Backend core concepts)

Read `doc/modules/ROOT/pages/coreconcepts.adoc` and `doc/modules/ROOT/pages/edge/architecture.adoc` for the full picture; short version:

- **OSGi Bundle**: every top-level `io.openems.*` directory is one OSGi bundle with its own `bnd.bnd`. Naming conventions: `*.api` bundles hold Nature/interface definitions only (e.g. `io.openems.edge.meter.api`), `*.common` bundles hold shared code, `*.core` bundles hold central singleton services (e.g. `io.openems.edge.core` has `ComponentManager`, `Cycle`, `Host`, `Sum`).
- **OpenEMS Component**: the fundamental building block; a Java class implementing `OpenemsComponent`, identified by a unique Component-ID (e.g. `ess0`, `meter1`). Wired together at runtime via OSGi service references (`@Reference`), so systems can be reconfigured without a restart.
- **Channel**: a single typed data point on a Component (`Component-ID/Channel-ID`, e.g. `ess0/Soc`). Channels always have a `value` (active, immutable within a cycle) and `nextValue` (latest received); access via `getOrError()`/`orElse()` because a value can always be `null`/undefined.
- **Nature**: an interface (in an `*.api` bundle) that bundles a set of required Channels for a device/service category (e.g. `ElectricityMeter`, `SymmetricEss`, `ManagedSymmetricEss`, `Evcs`). Controllers program against Natures, not concrete device implementations.
- **Controller**: holds one encapsulated piece of control logic (`io.openems.edge.controller.*`), executed once per Cycle. Northbound connections (backend link, REST/websocket APIs) are also implemented as Controllers so external setpoints are still subject to local prioritization.
- **Scheduler**: orders Controller execution each Cycle; later Controllers cannot override values a higher-priority Controller already set.
- **Cycle** (`io.openems.edge.core/.../CycleImpl.java`): drives the Input-Process-Output loop, ~once/second. Input phase freezes a **Process Image** (copies `nextValue` → `value` for every Channel) so data can't change mid-cycle; Process phase runs Controllers in Scheduler order; Output phase applies results to hardware.
- Async I/O (Modbus, HTTP, MQTT, etc. bridges) runs on its own threads and synchronizes with the Cycle via Cycle Events — see `io.openems.edge.bridge.modbus`'s `AbstractModbusBridge`.

Implementing a new device: `doc/modules/ROOT/pages/edge/implement.adoc` walks through creating a bundle, defining `Config.java` (OSGi `@ObjectClassDefinition`/`@AttributeDefinition`), implementing the Component (typically extending `AbstractOpenemsModbusComponent` or `AbstractOpenemsComponent`), defining a `ModbusProtocol` mapping registers to Channels, and writing a `ComponentTest`-based JUnit test.

## Edge/Backend/UI communication

All three parts talk over a JSON-RPC-over-WebSocket protocol (`doc/modules/ROOT/pages/component-communication/index.adoc`):

- `subscribeChannels` request + repeated `currentData` notifications for real-time Channel values.
- `edgeRpc` wraps a JSON-RPC payload with an `edgeId` so the Backend can transparently forward it to a specific Edge (e.g. `getEdgeConfig`, `updateComponentConfig`).
- `componentJsonApi` targets a single Component that implements `JsonApi` directly.
- `getEdgeConfig` returns the full `EdgeConfig` (all Components, their Channels and metadata) — this is what the UI uses to build its views.

## Coding guidelines (from CONTRIBUTING.md / coding-guidelines.adoc)

- Java: target Java 21 idioms (`var`, streams, lambdas). Split each component into interface + `...Impl` + `Config`. Keep pull requests to one bundle/directory. No `System.out.println`; remove stray `console.log`/debug logging. Comment only non-obvious code.
- Format with the Eclipse built-in formatter and organize imports before committing; checkstyle config is `cnf/checkstyle.xml`.
- Add a `readme.adoc` to new bundles; keep `bnd.bnd` build/test paths alphabetically sorted (`tools/prepare-commit.sh` can auto-sort `bnd.bnd`'s buildpath).
- Add JUnit tests for new components (`ComponentTest` / `AbstractComponentTest.TestCase` framework under `io.openems.edge.common.test`).
- TypeScript/UI: same general principles as Java where applicable.
- Git-Flow: `develop` is the integration branch; PRs should be small and focused, self-reviewed on GitHub's "Files" tab before requesting review.

## Custom Fronius/Hoymiles bundles (not upstream, in production use)

Four Edge bundles in this working copy are custom additions, not part of upstream OpenEMS: they are currently **untracked in git** but wired into `io.openems.edge.application/EdgeApp.bndrun` (`-runrequires`/`-runbundles`) and running productively. They were written with AI assistance in prior sessions. Each has a detailed German-language `readme.adoc` in its own directory — read that first before changing the bundle, it documents non-obvious API quirks and prior bugs fixed.

- **`io.openems.edge.meter.fronius.smartmeter.json`** — `ElectricityMeter` reading a Fronius Smart Meter through the GEN24's `GetMeterRealtimeData.cgi` JSON endpoint (no Modbus). Factory-ID `Meter.Fronius.SmartMeterJson`.
- **`io.openems.edge.pvinverter.fronius.json`** — PV production meter (`ElectricityMeter` + `MeterType.PRODUCTION`) for a Fronius GEN24/Symo inverter, reading `GetInverterRealtimeData.cgi` + `GetPowerFlowRealtimeData.fcgi`. Factory-ID `PvInverter.Fronius.Json`. Deliberately reads `ActivePower` from `Site.P_PV`, not `PAC`, because on a hybrid GEN24 with DC-coupled battery `PAC` is net-of-battery-charging, not true PV yield.
- **`io.openems.edge.ess.fronius.json`** — `ManagedSymmetricEss` for the GEN24's battery. Reading (SoC, power, capacity) uses the official `GetStorageRealtimeData.cgi`. Writing/control (`controlMode != READ_ONLY`) uses Fronius's **undocumented, reverse-engineered** Web-Config API (Digest-Auth, firmware-dependent hash/paths), ported from the Python reference implementation `batcontrol`. Defaults to `controlMode = READ_ONLY` (never writes) — deliberately conservative because the write API is unofficial and can break with firmware updates.
- **`io.openems.edge.pvinverter.hoymiles.opendtu`** — PV production meter for Hoymiles microinverters via an OpenDTU device's `/api/livedata/status` JSON API (no Modbus, no cloud). Factory-ID `PvInverter.Hoymiles.OpenDtu`. Supports aggregate (all inverters summed) or single-inverter mode via the `inverterSerial` config field.

Cross-cutting patterns shared by all four (worth knowing before touching any of them):

- All use `io.openems.common.bridge.http`/`io.openems.edge.bridge.http` (`BridgeHttp`/`HttpBridgeCycleService`) to poll JSON HTTP endpoints on the Cycle, not Modbus. A `pollEveryCycles` config field (default `3`) throttles polling to a multiple of the OpenEMS Cycle time, since these devices' web servers often respond slower than the 1s cycle (harmless "Task is not queued twice" INFO logs otherwise).
- On device unreachable/error, all Channels are set to `null` and `SLAVE_COMMUNICATION_FAILED` is set; no manual retry/backoff needed since `BridgeHttp` already handles that.
- **UI production tile gotcha**: for the PV-inverter bundles, `getMeterType()` alone being `PRODUCTION` at runtime is not enough for the Energy Monitor widget in OpenEMS UI to show a production tile — the UI (`ui/src/app/shared/edge/edgeconfig.ts`, `isProducer()`) only trusts the `type` property when it comes from a real `@AttributeDefinition`/Metatype config field transmitted via `EdgeConfig`, not a hardcoded `getMeterType()` return. Both bundles therefore expose a real `Config#type()` attribute (default `PRODUCTION`) instead of hardcoding it.
- **ESS power-solver gotcha**: `ManagedSymmetricEss` implementations that never set `AllowedChargePower`/`AllowedDischargePower`/`MaxApparentPower` get forced to a 0 W setpoint by every Controller, because `ConstraintUtil.createGenericEssConstraints()` defaults missing values to `0` and builds `>=0` and `<=0` constraints simultaneously. The Fronius ESS bundle sets these once at `activate()` from config fields (`batteryMaxChargePowerWatt` etc.), since the Solar API doesn't provide them.
- Sign conventions matter and have bitten this project before: OpenEMS defines `ACTIVE_PRODUCTION_ENERGY`/`ACTIVE_CONSUMPTION_ENERGY` as integrals over positive/negative `ActivePower`, which does **not** line up with Fronius's own field names "Consumed"/"Produced" — mapping by name instead of by sign previously swapped grid-import/export in the UI history. Similarly `SymmetricEss.ACTIVE_POWER` is negative-while-charging, opposite of Fronius's `Current_DC` convention.
- `io.openems.edge.application/fronius-ess-ess0-timeofuse-backup.json` is a **runtime-generated backup file** written by the ESS bundle the first time it overwrites the GEN24's existing time-of-use schedule (so the original schedule can be restored on deactivation) — it's not a source/config file to hand-edit, and its presence/absence is meaningful state.
- None of these four bundles are upstream OpenEMS; don't expect them to show up in `git log`/`git blame` history or be covered by `doc/modules/ROOT` docs — their `readme.adoc` is the only documentation.

## UI structure notes

- `ui/src/app/edge/*` — views/services specific to being connected to an Edge (live, history, settings).
- `ui/src/app/index/*` — the landing/index views (overview, registration, filters) used when connected through a Backend with multiple Edges.
- `ui/src/app/shared/*` — cross-cutting services (jsonrpc client, ngrx-store, i18n, permissions, pipes, utils).
- `ui/src/themes/{fenecon,openems,shared}` — white-label theming; `ng build`/`ng serve` configurations select a theme + target (`-edge-`/`-backend-`) + environment (`-dev`/`-prod`).
- i18n via `ngx-translate`: templates use `<p translate>Path.To.Key</p>`, code uses `TranslateService.instant(...)`; keys live in `ui/src/app/shared/i18n`. `npm run lint:i18n` checks for unused/missing keys.
- Always unsubscribe RxJS subscriptions via a `takeUntil`-on-`ngOnDestroy` `Subject` pattern (see existing components for the idiom).
