# modb-app

The application and libraries which build
[anime-offline-database](https://github.com/anime-offline-database/anime-offline-database). _modb_
stands for **M**anami **O**ffline **D**ata**B**ase.

This repository continues [manami-project/modb-app](https://github.com/manami-project/modb-app),
archived on 2026-07-02. The original author stopped maintaining it, and the projects that depend on
the dataset had no way forward. This fork exists to keep the dataset updated.

Do not use these crawlers to download a metadata provider in full. Check whether the dataset already
contains what you need first, and use it if so.

## Modules

| Module | Purpose |
|---|---|
| `app` | Runs the crawlers, merges anime, updates the dataset repository. |
| `analyzer` | Reviews entries and creates merge locks. |
| `core` | Shared functionality. |
| `lib` | Drives `app` and `analyzer`. |
| `serde` | Reads and writes the dataset files. |
| `kommand` | Vendored from [manami-project/kommand](https://github.com/manami-project/kommand). See below. |
| `anidb`, `anilist`, `anime-planet`, `animenewsnetwork`, `anisearch`, `kitsu`, `livechart`, `myanimelist`, `simkl` | Config, downloader and converter per metadata provider. |

## Requirements

* JDK 25 or higher
* A Linux or Unix system with `make`, `bash`, `jsonschema`, `gh` and `git`

## Building

```
./gradlew assemble
```

No credentials are needed. `kommand` used to resolve from GitHub Packages, which rejects anonymous
callers and is published from a repository that is now archived, so a clone could not be built
without a token. It is vendored here as a subproject under its own AGPL-3.0 licence instead.

## Starting from an existing dataset

The pipeline keeps two kinds of state: download control state, which schedules what to re-download
and rebuilds itself after a full pass, and merge locks, which record which entries belong together.
Merge locks are not published, but a merge lock is a set of source URIs, which is exactly what an
entry's `sources` already are. Any published dataset therefore seeds them:

```
./gradlew :lib:seedMergeLocks --args="anime-offline-database.jsonl.zst /path/to/dcs-directory"
```

Seeding from the final upstream release produces 29,549 merge locks covering 177,593 sources.

## Outgoing connections

The anidb and anisearch crawlers change their outgoing IP address when a provider starts refusing
requests. Two implementations of `NetworkController` are available:

* `LinuxNetworkController` restarts the network device so a SLAAC enabled IPv6 connection hands out
  a new address. This is the original behaviour and needs a routed prefix, `sudo` and `ifconfig`.
* `RotatingProxyNetworkController` advances through a pool of proxies configured under
  `modb.app.network.proxies`. Use this where restarting the interface returns the same address,
  which is the case on most hosted machines.

## Licence

AGPL-3.0, the same as the original. If you run a modified version of this software as a network
service, you must offer its source to the users of that service.

The dataset this software produces is published separately under the Open Database License.
