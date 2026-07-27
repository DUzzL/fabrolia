<div align="center">

# Fabrolia - Minecraft Entity Multi-Threading Mod ⚙️

[![Modrinth Downloads](https://img.shields.io/modrinth/dt/fabrolia?style=for-the-badge&logo=modrinth)](https://modrinth.com/mod/fabrolia)
[![Discord](https://img.shields.io/discord/YOUR_DISCORD_ID?style=for-the-badge&logo=discord&label=Discord)](https://discord.com/invite/scvCQ2qKS3)
[![GitHub Issues](https://img.shields.io/github/issues/AxalotLDev/Fabrolia?style=for-the-badge)](https://github.com/AxalotLDev/Fabrolia/issues)
</div>



**Fabrolia** is a Fabric mod designed to improve entity performance by processing them in parallel using multiple CPU cores and threads.


## Important❗
**Fabrolia** is currently in alpha testing and is experimental. Its use may lead to incorrect entity behavior and crashes.



### 💡 Key Benefits:
- ⚡ **Improved TPS**: Maintains stable tick times even with a large number of entities.
- 🚀 **Multithreading**: Utilizes multiple CPU cores for parallel entity processing.
- 🎲 **Fabrolia Random Ticks** (Experimental): Processes random ticks asynchronously for better performance.

### 📊 Performance Comparison (9000 Villagers)
| Configuration           | TPS  | MSPT   |
| ----------------------- | ---- | ------ |
| **Lithium + Fabrolia**     | 20   | 41.8   |
| **Lithium (without Fabrolia)** | 4.4  | 225.4  |
| **Purpur**              | 5.72 | 176.18 |

### 🛠️ Test Configuration
- **Processor**: AMD Ryzen 9 7950X3D
- **RAM**: 64 GB (16 GB allocated to the server)
- **Minecraft Version**: 1.21.4
- **Number of Entities**: 9000
- **Entity Type**: Villagers

<details>
<summary>Mod List</summary>
Concurrent Chunk Management Engine, Fabric API, FerriteCore, Lithium, ScalableLux, ServerCore, StackDeobfuscator, TT20 (TPS Fixer), Tectonic, Very Many Players, Fabric Carpet.
</details>

## ⚠️ Incompatible Mods
- ❌ Moonrise - Known incompatibility
- ⚠️ ...and there may be conflicts with other mods.

*If you encounter issues with other mods, please report them on our [GitHub](https://github.com/AxalotLDev/Fabrolia/issues) or [Discord](https://discord.com/invite/scvCQ2qKS3).*

## 🔧 Commands
- `/fabrolia config toggle` — Enables or disables the mod in-game (no server restart required). Use this command to instantly see how Fabrolia improves your server.
- `/fabrolia config setFabroliaEntitySpawn` — Enables or disables parallel mob spawn processing (disabled by default). **Warning: Not compatible with Carpet mod lagFreeSpawning rule.**
- `/fabrolia config setFabroliaRandomTicks` — Enables or disables fabrolia random ticks processing (experimental feature).
- `/fabrolia config synchronizedEntities add` — Adds selected entity to synchronized processing.
- `/fabrolia config synchronizedEntities remove` — Removes selected entity from synchronized processing.
- `/fabrolia stats` — Displays the number of threads in use.
- `/fabrolia stats entity` — Shows the number of entities processed by Fabrolia in various worlds.
- `/fabrolia stats entity [number]` — Shows the top [number] entity types by count in descending order. For example, `/fabrolia stats entity 10` displays the top 10 most numerous entity types.

## 📥 Download
The mod is available on [Modrinth](https://modrinth.com/mod/fabrolia)

## 🔄 Minecraft Version Support
Full support is provided only for the latest version of Minecraft. Older versions receive critical fixes only. Support for older Minecraft snapshots is not planned.

## 📭 Feedback
Our tracker for feedback and bug reports is available on GitHub:
[![Report issues on GitHub](https://img.shields.io/badge/Report%20issues%20on-GitHub-lightgrey)](https://github.com/AxalotLDev/Fabrolia/issues)

You can also chat with us on Discord:
[![Chat with us on Discord](https://img.shields.io/badge/Chat%20with%20us%20on-Discord-blue)](https://discord.com/invite/scvCQ2qKS3)

## 🙌 Acknowledgements
This mod is based on code from [MCMTFabric](https://modrinth.com/mod/mcmtfabric), which in turn was based on [JMT-MCMT](https://github.com/jediminer543/JMT-MCMT). Huge thanks to Grider and jediminer543 for their invaluable contributions!