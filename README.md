[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stars][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![License][license-shield]][license-url]

# Sources Requests

Open up an Issue with these contents:

- URL
- Name
- language?
- NSFW yes/no

Or join the Support Server or contact me over Telegram (see Contact Info)

---

# Contact

### Support Server

[![Discord][discord-shield]][discord-url]

### Direct Contact

[![Telegram][telegram-shield]][telegram-url]

---

# Tutorial

<details>
<summary><b>How to add a Plugin in Usagi</b></summary>

<br>

There are actually two ways, one which you manually update the Plugin from time to time **or** let it do automatically.

<br>

---

<details>
<summary><b>Manually way</b></summary>

<br>

1. Download the latest version of the Plugin from the [Releases](https://github.com/InvalidDavid/UMA/releases) page.
2. Click on the blue Name or press the three dots on the right side and press Download.
<details>
<summary>📷 Show Screenshot</summary>

![Screenshot][img3]

</details>

3. Go back to the App and open up `Explore`

<details>
<summary>📷 Show Screenshot</summary>

![Screenshot][img1]

</details>

4. Click on `three Dots in the top right corner` and select `Manage Sources`

<details>
<summary>📷 Show Screenshot</summary>

![Screenshot][img2]

</details>

5. Go to `Manage Plugins` and press the `Add` / `+` button in the top right corner.
6. Select `Import from local storage` and select `UMA.jar` file which you downloaded in the previous step.

And that's it! Select your new sources in the catalog and have fun reading.
</details>

<br>

---

<details>
<summary><b>Automatically</b></summary>

<br>

1. Open up `Explore`

<details>
<summary>📷 Show Screenshot</summary>

![Screenshot][img1]


</details>

<br>

2. Click on `three Dots in the top right corner` and select `Manage Sources`

<details>
<summary>📷 Show Screenshot</summary>

![Screenshot][img2]

</details>

<br>

3. Go to `Manage Plugins` and press the `Add` / `+` button in the top right corner.

4. Select `Import from Github` and enter the URL:

Repository URL:
```text

https://github.com/InvalidDavid/UMA

```
or
```text

InvalidDavid/UMA

```
5. Press then `OK`.

And that's it! Select your new sources in the catalog and have fun reading.

> **Note:** The plugin will not update automatically unless you enable this feature (see screenshot).  
> You can manually check anytime in `Manage Plugins` for an `Install` button.

<details>
<summary>📷 Show Screenshot</summary>

![Screenshot][img4]

</details>


</details>

</details>

---

# Setup

<details>
<summary><b>View Plugin Information</b></summary>

<br>

This template project provides a collection of utilities and some parsers for convenient access to any content available on the web.

## Requirements

* Android Studio or IntelliJ IDEA (Community Edition is enough)
* Android SDK 35 or later (if not using IDE)
* Java 11 or later is required

## Usage

### 1. Open Terminal on the root folder and build the project

#### Linux & Unix

```bash
chmod +x gradlew && ./gradlew buildJar
```

#### Windows

```cmd
.\gradlew.bat buildJar
```

### Alternative

**More simply, just run `buildJar` task in Android Studio / IntelliJ IDEA and dex it after building.**

</details>

---

# Credits

* Thanks to [KotatsuApp](https://github.com/KotatsuApp) for providing some parsers and the core library.
* Thanks to [Kotatsu Redo Parser](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo) for providing some extension code on GitHub.
* Thanks to [Keiyoushi](https://github.com/Keiyoushi) for providing some extension code on GitHub.

---

# License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

You may copy, distribute and modify the software as long as you track changes/dates in source files. Any modifications to or software including (via compiler) GPL-licensed code must also be made available under the GPL along with build and install instructions. See [LICENSE](./LICENSE) for more details.

[license-shield]: https://img.shields.io/github/license/InvalidDavid/UMA.svg?style=for-the-badge
[license-url]: https://github.com/InvalidDavid/UMA/blob/master/LICENSE
[contributors-shield]: https://img.shields.io/github/contributors/InvalidDavid/UMA.svg?style=for-the-badge
[contributors-url]: https://github.com/InvalidDavid/UMA/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/InvalidDavid/UMA.svg?style=for-the-badge
[forks-url]: https://github.com/InvalidDavid/UMA/network/members
[stars-shield]: https://img.shields.io/github/stars/InvalidDavid/UMA.svg?style=for-the-badge
[stars-url]: https://github.com/InvalidDavid/UMA/stargazers
[issues-shield]: https://img.shields.io/github/issues/InvalidDavid/UMA.svg?style=for-the-badge
[issues-url]: https://github.com/InvalidDavid/UMA/issues
[discord-shield]: https://img.shields.io/discord/1518057632064209017?label=Discord&logo=discord
[discord-url]: https://discord.gg/CyJeVDP7Cw
[telegram-shield]: https://img.shields.io/badge/-Telegram-black.svg?style=for-the-badge&logo=telegram&colorB=555
[telegram-url]: https://t.me/invalidxdavid

[img1]: https://cdn.discordapp.com/attachments/1518283800830939176/1519672301883363409/image.png?ex=6a3e689e&is=6a3d171e&hm=fca40dfdf33ea99b07e7237b4d9a44afea1991be7b59f750a6bc9a906146c53e&
[img2]: https://cdn.discordapp.com/attachments/1518283800830939176/1519672539532628068/image.png?ex=6a3e68d7&is=6a3d1757&hm=1551bf57fde5d3e8c7cb53c182347bfcb3b2ff58fa297c88148338e88c2b5fe1&
[img3]: https://cdn.discordapp.com/attachments/1518283800830939176/1519673238316126268/image.png?ex=6a3e697e&is=6a3d17fe&hm=ed8e12ca85af9b8aa461463bf8095d958e46614e5478b97f95363875a81ab62b&
[img4]: https://cdn.discordapp.com/attachments/1518283800830939176/1519673924554588321/image.png?ex=6a3e6a21&is=6a3d18a1&hm=2321b2d2a632080101312427741a790be7b1c3c15fd32cb82e5e3ba86fcfbe39&
