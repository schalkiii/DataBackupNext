<div align="center">

<span style="font-weight: bold"> <a> English </a> </span>

<img src="./fastlane/metadata/android/en-US/images/icon.png" alt="logo" width="128px" />

<h1 align="center">DataBackup</h1>

[![Doc](https://img.shields.io/badge/wiki-documentation-forestgreen)](https://DataBackupOfficial.github.io)
[![Download](https://img.shields.io/github/downloads/XayahSuSuSu/Android-DataBackup/total)](https://github.com/XayahSuSuSu/Android-DataBackup/releases)
[![GitHub release](https://img.shields.io/github/v/release/XayahSuSuSu/Android-DataBackup?color=orange)](https://github.com/XayahSuSuSu/Android-DataBackup/releases)
[![License](https://img.shields.io/github/license/XayahSuSuSu/Android-DataBackup?color=ff69b4)](./LICENSE)
[![Channel](https://img.shields.io/badge/channel-DataBackup-252850?color=blue&logo=telegram)](https://t.me/dabackupchannel)
[![Chat](https://img.shields.io/badge/group-DataBackup-252850?color=blue&logo=telegram)](https://t.me/databackupchat)
[![Translation](https://hosted.weblate.org/widget/databackup/svg-badge.svg)](https://hosted.weblate.org/engage/databackup/)

Free and open-source data backup application

</div>

## Overview
<a href="https://hellogithub.com/repository/3e9dc382d4764688856238a83616de5b" target="_blank"><img src="https://abroad.hellogithub.com/v1/widgets/recommend.svg?rid=3e9dc382d4764688856238a83616de5b&claim_uid=POXv2xVC71JHihc&theme=neutral" alt="Featured｜HelloGitHub" style="width: 250px; height: 54px;" width="250" height="54" /></a>

:star: Based on [speed-backup](https://github.com/YAWAsau/backup_script) by [CoolApk@落叶凄凉TEL](http://www.coolapk.com/u/2277637).

:fire: The [script](https://github.com/YAWAsau/backup_script) has been **acclaimed** since the [author](https://github.com/YAWAsau) wrote it.

:sparkling_heart: This application was born **with the consent of the author**.

## Usage
See [documentation](https://DataBackupOfficial.github.io).

## Features
* :deciduous_tree: **Root needed, support [Magisk](https://github.com/topjohnwu/Magisk), [KernelSU](https://github.com/tiann/KernelSU), [APatch](https://github.com/bmax121/APatch)**

* :cyclone: **Multi-user Support**

* :cloud: **Cloud**

* :sunglasses: **100% Data Integrity**

* :zap: **Fast**

* :sunny: **Easy**

* :arrows_counterclockwise: **Updated Apps Detection**

* :package: **Multiple Backup Copies**

* :satellite: **Incremental Cloud List Sync**

* :date: **Sort By Last Backup Time**

* :compare_arrows: **Counterpart Version In Details**

* :rose: **...**

## Differences from upstream
This repository is a fork of [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup) (based on v2.0.12, the 2.x track under `source/`), maintained independently with a series of backup-capability enhancements:

* :arrows_counterclockwise: **Updated apps detection (bidirectional)** — the backup view filters apps whose *installed* version is newer than the backed-up one, while the restore view filters apps whose *cloud backup* version is newer than the installed one. The filter is off by default; checking it keeps only outdated apps.

* :date: **Sort by last backup time** — new sort option (ascending/descending) driven by a derived backup ledger aggregated from existing RESTORE entities, without an extra database table.

* :satellite: **Incremental cloud list sync** — a manifest (`apps_index.json`) is uploaded at the end of every cloud backup; loading the backed-up list downloads this single file instead of walking the whole remote tree, with graceful fallback to the legacy full scan. Archive sizes are preset from the manifest, so restore details no longer show 0 B.

* :package: **Multiple backup copies** — configurable retention count (default 1, backward compatible); before each backup the current main copy is rotated to a timestamped copy and copies beyond the limit are cleaned up, log-rotation style.

* :compare_arrows: **Counterpart version in details** — the app details page shows the other side's version: latest backup version on the backup view, installed local version on the restore view.

* :bell: **Backup reminder badge** — the backup list subtitle shows the count of apps whose local version is newer than the backup, so pending refreshes are visible at a glance.

* :history: **History copies & point-in-time restore** — the app details page (restore view) lists all copies of the same package with backup time; selecting a copy activates it as the restore target, and the previous active copy is deactivated automatically.

* :floppy_disk: **Persistent filter conditions** — list filter states (backup view and restore view separately) are persisted in DataStore and restored on the next visit.

* :arrow_right: **Inline version transition badge** — next to the update icon, the list item shows the version transition in a compact form (`1.0 → 1.2`): local → backup on the backup view, cloud → local on the restore view.

See [docs/backup-enhancement-proposal.md](./docs/backup-enhancement-proposal.md) for the full design, implementation records (appendices A-D) and the future roadmap (appendix E).

## Screenshot
<div align="center">
	<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/01.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/02.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/03.jpg" width="275px">
	<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/04.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/05.jpg" width="275px"><img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/06.jpg" width="275px">
</div>

## Download
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png"
     alt="Get it on IzzyOnDroid"
     height="80">](https://apt.izzysoft.de/fdroid/index/apk/com.xayah.databackup.foss)[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80">](https://f-droid.org/zh_Hans/packages/com.xayah.databackup.foss/)

or get the APK from the [Releases](https://github.com/XayahSuSuSu/Android-DataBackup/releases/latest).

## Translation
[<img src="https://hosted.weblate.org/widget/databackup/main/open-graph.png"
     alt="Translation">](https://hosted.weblate.org/engage/databackup/)

## Contributors
Thanks to all these wonderful people!

[![Contributors](https://contrib.rocks/image?repo=XayahSuSuSu/Android-DataBackup)](https://github.com/XayahSuSuSu/Android-DataBackup/graphs/contributors)

## Support
If you enjoy this app and want to help it become better, feel free to sponsor me!

<!-- [<img src="./docs/static/img/bmc-button.svg"
     alt="Buy Me a Coffee"
     height="60">](https://www.buymeacoffee.com/XayahSuSuSu)[<img src="./docs/static/img/afdian.svg"
     alt=爱发电
     height="60">](https://afdian.net/a/XayahSuSuSu) -->

[<img src="./docs/static/img/pp_h_rgb.svg"
     alt="PayPal"
     height="60">](https://paypal.me/XayahSuSuSu)

[<img src="./docs/static/img/afdian.svg"
     alt=爱发电
     height="60">](https://afdian.net/a/XayahSuSuSu)

## LICENSE
[GNU General Public License v3.0](./LICENSE)
