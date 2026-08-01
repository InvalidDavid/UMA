# Approved Source Inventory

This inventory is the acceptance boundary for the `en-ru` catalog expansion.

## English additions (49)

`ALLPORNCOMIC`, `AQUAMANGA`, `ASURASCANS`, `ATSUMARU`, `BATCAVE`, `COFFEEMANGA`, `COMICLAND`, `DEMONICSCANS`, `ENTHUNDERSCANS`, `FLAMECOMICS`, `HENTAIXCOMIC`, `HEYTOON`, `KALISCAN`, `KINGOFSHOJO`, `LIKEMANGA`, `MADARADEX`, `MANGA18`, `MANGABALL_EN`, `MANGADISTRICT`, `MANGADOTNET`, `MANGAFIRE_EN`, `MANGAFOX`, `MANGAGG`, `MANGAGO`, `MANGAJINX`, `MANGAK`, `MANGAKATANA`, `MANGAKEKO`, `MANGAPLUSPARSER_EN`, `MANGAREAD`, `MANGATAROORG`, `MANGATOWN`, `MANHUAUS`, `MANHWA18`, `MANHWA18CC`, `MANHWA210`, `MANHWANEX`, `MANHWATOP`, `MANHWAZ`, `MGREADIO`, `NOVELCROW`, `OMEGASCANS`, `TOPMANHUA`, `TWENTYFOURHNOVEL`, `WEBTOONS_EN`, `WEEBCENTRAL`, `ZINMANGA`, `MANGABAT`, `MANGANATOGG`.

`HENTAINEXUS` is not an addition because the local catalog already contains it. `HIPERDEX` is excluded because the UMA source is marked broken. `TWENTYFOURHNOVEL` remains exposed with `isBroken=true` because its domain is parked.

## Russian additions (27)

`ACOMICS`, `ALLHENTAI`, `BEST_MANGA`, `COMX`, `DESUME`, `HENCHAN`, `HENTAILIB`, `MANGABALL_RU`, `MANGACHAN`, `MANGALIB`, `MANGAMAMMY`, `MANGAONELOVE`, `MANGAPLUSPARSER_RU`, `MANGAZAVR`, `MANGA_WTF`, `MINTMANGA`, `NINEMANGA_RU`, `NUDEMOON`, `READMANGA_RU`, `REMANGA`, `SEIMANGA`, `SELFMANGA`, `USAGI`, `WAMANGA`, `YAOICHAN`, `YAOILIB`, `ZENMANGA`.

`ACOMICS`, `ALLHENTAI`, `BEST_MANGA`, `HENTAILIB`, `MANGAZAVR`, `MINTMANGA`, `NINEMANGA_RU`, and `ZENMANGA` remain exposed with `isBroken=true` until repair contracts pass against viable endpoints.

## Source snapshots

- English and Manga Ball sources: InvalidDavid/UMA commit `1b0db5ae2de9480b4fcb2b8d0b2ee0537870d7e1`.
- Russian sources: KotatsuApp/kotatsu-parsers commit `4d1e521aef7e4d9f41c65adda5e274509e93807c`.
