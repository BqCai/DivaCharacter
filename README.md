# DIVA Character

DIVA Character 在《僵尸毁灭工程》中通过 Project Viewpoint 渲染初音未来，并支持本地换装和部分单人 PV 预览。

DIVA Character renders Hatsune Miku through Project Viewpoint in Project Zomboid, with local outfit imports and previews of supported solo PVs.

- [首测版说明与安装教程](docs/releases/v0.1.0-alpha.1.md#简体中文)
- [Initial test release notes and installation](docs/releases/v0.1.0-alpha.1.md#english)
- [Third-party notices](THIRD-PARTY-NOTICES.md)

项目代码许可证待作者确定。第三方组件的许可与署名见上方声明。

The project code license is pending the author's decision. See the notices above for third-party licenses and credits.

## Build the test package

On Windows, install Python and JDK 25. Point `DIVA_JDK` to the JDK directory if it differs from the build script's default. Copy `project.local.example.json` to `project.local.json` and enter the paths to Project Zomboid, ZombieBuddy, and the directory containing the MikuMikuLibrary v2.2.0 DLL. The Windows .NET Framework C# compiler builds the import helper.

```powershell
python tools/build_release.py
```

The builder writes a ZIP under `build/release` and prints its SHA-256. The package contains the mod, the importer, and the public shader. The F8 menu reads outfits and PV resources from the player's local Mega Mix+ installation.
