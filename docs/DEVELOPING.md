# Developing OSParty

## Requirements

Requires **JDK 11**, to match the RuneLite client
(`sourceCompatibility`/`targetCompatibility = VERSION_11` in
[`build.gradle`](../build.gradle#L34-L37)). The Gradle wrapper resolves `net.runelite:client`
at `latest.release` from `https://repo.runelite.net` (plus `mavenLocal()` and `mavenCentral()`
for everything else) — see [`build.gradle:1-29`](../build.gradle#L1-L29). There's no pinned
RuneLite version to bump; a plain `./gradlew build` always builds against whatever RuneLite most
recently released.

Local persistence (favourites/block lists, party history, credentials) is plain JSON via
RuneLite's own bundled Gson — see [`store/JsonFile.java`](../src/main/java/net/osparty/store/JsonFile.java)
and [ARCHITECTURE.md](ARCHITECTURE.md#local-storage) — so there's no extra runtime dependency to
add, which is also what the Plugin Hub's dependency verification expects.

## Running a dev client

[`OSPartyPluginTest.main`](../src/test/java/net/osparty/OSPartyPluginTest.java) loads the plugin
into RuneLite's `ExternalPluginManager` and starts the client, for local testing without
installing through the Plugin Hub:

```java
ExternalPluginManager.loadBuiltin(OSPartyPlugin.class);
RuneLite.main(args);
```

The simplest way to run it is the bundled Gradle task:

```
./gradlew runClient
```

This runs `OSPartyPluginTest` on the `test` source set's runtime classpath. Every `JavaExec`
task (this one included) automatically gets the JVM flags RuneLite needs on a modern JDK —
assertions enabled (`-ea`, required by `ExternalPluginManager.loadBuiltin()`) and the
`--add-opens` needed because the client touches JDK-internal AWT APIs JDK 9+ encapsulates — see
[`build.gradle:51-64`](../build.gradle#L51-L64). Running `OSPartyPluginTest.main` directly from
an IDE instead of through Gradle needs those flags added by hand (IntelliJ's "run main"
delegation to Gradle picks them up automatically).

## Pointing at a local listing service

The plugin talks to the listing service at `https://api.osparty.net` by default. There's no
in-settings URL field — the base URL is resolved once, from a JVM system property, at class
load:

```java
// BoardApiClient.resolveBaseUrl()
String property = System.getProperty("osparty.apiUrl");
```

([`api/BoardApiClient.java:20-32`](../src/main/java/net/osparty/api/BoardApiClient.java#L20-L32))

To develop against your own [ospartyapi](https://github.com/osparty/ospartyapi) instance, set it
before the client starts:

```
-Dosparty.apiUrl=http://localhost:8080
```

Any `-Dosparty.*` system property passed to the **Gradle invocation itself** is forwarded into
the forked client JVM automatically — `runClient` copies every system property whose name starts
`osparty.` onto the task before launching:

```groovy
// build.gradle:73-84
tasks.register('runClient', JavaExec) {
    ...
    System.properties.each { k, v ->
        if (k.toString().startsWith('osparty.')) {
            systemProperty(k.toString(), v)
        }
    }
}
```

So the working invocation is:

```
./gradlew runClient -Dosparty.apiUrl=http://localhost:8080
```

The list starts empty against a fresh local backend; Search shows parties once something
advertises them. Running `OSPartyPluginTest.main` directly (outside Gradle) instead needs
`-Dosparty.apiUrl=…` passed straight to that JVM, since there's no forwarding step to do it for
you.

## Recognised system properties

System properties are the only external configuration the plugin reads. There are no environment
variables by design: the plugin hub disallows `System.getenv` outright, which
[a reviewer enforced on this plugin's own submission](https://github.com/runelite/plugin-hub/pull/13020)
(*"use of getenv is not allowed; use a jvm sysprop instead"*).

| Property | Default | Effect |
|---|---|---|
| `osparty.apiUrl` | `https://api.osparty.net` | Base URL of the listing service ([`BoardApiClient`](../src/main/java/net/osparty/api/BoardApiClient.java#L20-L32)) |
| `osparty.deviceLabel` | the machine's resolved hostname | Name this install reports as `X-OSParty-Device`, used only as the starting label for a newly enrolled device ([`OSPartySocket.deviceLabel()`](../src/main/java/net/osparty/api/OSPartySocket.java#L71-L112)) |

`osparty.deviceLabel` exists both for machines whose hostname doesn't resolve and for anyone who
would rather not send theirs. When it's unset and the lookup fails, the label is simply omitted
and the server names the device after its enrolment date; it can be renamed from the device
manager regardless.

## Tests

`src/test` depends on JUnit 4 (`junit:junit:4.13.2`) and Mockito (`org.mockito:mockito-core:5.20.0`),
plus `net.runelite:client` and `net.runelite:jshell` at the same `latest.release` version used for
the main build — see [`build.gradle:25-28`](../build.gradle#L25-L28).
