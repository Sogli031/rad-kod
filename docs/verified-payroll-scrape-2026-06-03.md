# Firecrawl provera izvora za obracun plata

Datum scrape-a: 2026-06-03, oko 13:53-13:54 Europe/Belgrade.

Alat: Firecrawl CLI v1.19.0. Rezultati su sacuvani u `.firecrawl/`; folder je lokalni artefakt i dodat je u `.gitignore`. API key nije sacuvan u ovom projektu.

Ovaj dokument belezi sta je skrejpovano i koje informacije su relevantne za `rad` aplikaciju. Ne menja zakljucke iz `AUDIT_PLATA_KALKULACIJA.md`: poslodavacke vrednosti iz kolektivnog ugovora, aneksa ili obracunske liste i dalje moraju rucno da se potvrde pre promene formula.

## Skrejpovani izvori

| Izvor | URL | Lokalni Firecrawl fajl | Status / upotreba |
|---|---|---|---|
| Ministarstvo za rad, praznici | https://www.minrzs.gov.rs/srb-lat/ministarstvo/drzavni-i-verski-praznici-republike-srbije | `.firecrawl/minrzs-praznici.md` | Uspesno; citljiv zvanicni pregled neradnih drzavnih i verskih praznika. |
| Ministarstvo za rad, rad na praznik | https://www.minrzs.gov.rs/srb-lat/aktuelnosti/saopstenja/ap%D0%B5l-poslodavcima-da-postuju-prava-zaposl%D0%B5nih-koji-rad%D0%B5-u-dan%D0%B5-praznika | `.firecrawl/minrzs-praznik-uvecana-zarada.md` | Uspesno; citljiva potvrda minimuma za uvecanu zaradu na neradni praznik. |
| Ministarstvo za rad, minuli rad | https://www.minrzs.gov.rs/srb-lat/aktuelnosti/vesti/obracun-minulog-rada-ubuduc%D0%B5-isti-za-sv%D0%B5-zaposl%D0%B5n%D0%B5-u-srbiji | `.firecrawl/minrzs-minuli-rad.md` | Uspesno; zvanicna vest objasnjava minuli rad po Zakonu o radu. |
| Ministarstvo finansija, porez na dohodak | https://mfin.gov.rs/sr/propisi-1/zakon-o-porezu-na-dohodak-gradjana-1 | `.firecrawl/mfin-zakon-porez-na-dohodak.md` | Uspesno, ali uglavnom naslov/lista propisa i reCAPTCHA deo; koristi se za zvanicnu verziju/glasnike, ne za citljiv tekst clanova. |
| Ministarstvo finansija, doprinosi | https://mfin.gov.rs/sr/propisi-1/zakon-o-doprinosima-za-obavezno-socijalno-osiguranje-1 | `.firecrawl/mfin-zakon-doprinosi.md` | Uspesno, ali uglavnom naslov/lista propisa i reCAPTCHA deo; potvrdjuje da postoje posebne osnovice za 2026. |
| Paragraf, Zakon o radu | https://www.paragraf.rs/propisi/zakon_o_radu.html | `.firecrawl/paragraf-zakon-o-radu.md` | Uspesno; citljiv nezvanicno preciscen tekst zakona. |
| Paragraf, porez na dohodak | https://www.paragraf.rs/propisi/zakon-o-porezu-na-dohodak-gradjana.html | `.firecrawl/paragraf-zakon-porez-na-dohodak.md` | Uspesno; citljiv tekst za poresku stopu i poresku olaksicu. |
| Paragraf, neoporezivi iznosi | https://www.paragraf.rs/statistika/poreski_tretman_i_uskladjeni_neoporezivi_iznosi_za_odredjena_licna_primanja_zaposlenih_i_ostalih_fizickih_lica.html | `.firecrawl/paragraf-neoporezivi-iznosi.md` | Uspesno; direktno relevantno za `TaxParameterUpdater.kt`. |
| Paragraf, doprinosi | https://www.paragraf.rs/propisi/zakon-o-doprinosima-za-obavezno-socijalno-osiguranje.html | `.firecrawl/paragraf-zakon-doprinosi.md` | Uspesno; citljiv tekst stopa doprinosa i ogranicenja osnovica. |
| Paragraf, praznici | https://www.paragraf.rs/propisi/zakon_o_drzavnim_i_drugim_praznicima_u_republici_srbiji.html | `.firecrawl/paragraf-zakon-praznici.md` | Uspesno; citljiv tekst zakona o praznicima. |

## Potvrdjene informacije relevantne za aplikaciju

### Porez na zarade

- Paragraf tekst Zakona o porezu na dohodak prikazuje da se osnovica poreza na zarade za puno radno vreme umanjuje za 34.221 RSD mesecno (`.firecrawl/paragraf-zakon-porez-na-dohodak.md:586`) i da je stopa poreza na zarade 10% (`.firecrawl/paragraf-zakon-porez-na-dohodak.md:703`).
- Paragraf statisticka stranica dodatno navodi da je za isplate od 1. januara 2026. neoporezivi iznos zarade povecan na 34.221 RSD mesecno (`.firecrawl/paragraf-neoporezivi-iznosi.md:25`).
- Zakljucak za kod: trenutni `TaxParameterUpdater` ciljano trazi 10% i 34.221 RSD; scrape podrzava te vrednosti za 2026 i puno radno vreme.

### Doprinosi zaposlenog

- Paragraf tekst Zakona o doprinosima prikazuje stope: PIO 14% za relevantne zaposlene (`.firecrawl/paragraf-zakon-doprinosi.md:884`), zdravstveno 5,15% (`.firecrawl/paragraf-zakon-doprinosi.md:888`) i nezaposlenost 0,75% (`.firecrawl/paragraf-zakon-doprinosi.md:892`).
- Zbir za zaposlenog je 19,9%; Paragraf statisticka stranica isto navodi zbirnu stopu na teret zaposlenog od 19,9% (`.firecrawl/paragraf-neoporezivi-iznosi.md:46`).
- MFIN scrape ne daje pun tekst clanova, ali potvrdjuje da su za 2026 objavljene najnize/najvise osnovice doprinosa (`.firecrawl/mfin-zakon-doprinosi.md:7`, `.firecrawl/mfin-zakon-doprinosi.md:11`, `.firecrawl/mfin-zakon-doprinosi.md:13`).
- Zakljucak za kod: stopa 19,9% je potvrdjena, ali aplikacija i dalje ne modeluje najnizu/najvisu osnovicu doprinosa.

### Zakon o radu: uvecanja i naknade

- Rad nocu je vreme od 22:00 do 06:00 (`.firecrawl/paragraf-zakon-o-radu.md:941`).
- Minimum po Zakonu o radu: rad na neradni praznik 110%, nocni rad 26%, prekovremeni rad 26%, minuli rad 0,4% (`.firecrawl/paragraf-zakon-o-radu.md:1633`, `.firecrawl/paragraf-zakon-o-radu.md:1635`, `.firecrawl/paragraf-zakon-o-radu.md:1637`, `.firecrawl/paragraf-zakon-o-radu.md:1641`).
- Ministarstvo rada posebno potvrdjuje minimum 110% za rad na dan praznika koji je neradni dan (`.firecrawl/minrzs-praznik-uvecana-zarada.md:17`, `.firecrawl/minrzs-praznik-uvecana-zarada.md:21`).
- Naknada za odsustvovanje na dan praznika, godisnji odmor i placeno odsustvo vezuje se za prosecnu zaradu u prethodnih 12 meseci (`.firecrawl/paragraf-zakon-o-radu.md:1738`).
- Bolovanje do 30 dana: 65% za bolest/povredu van rada i 100% za povredu na radu/profesionalnu bolest, uz donju granicu minimalne zarade (`.firecrawl/paragraf-zakon-o-radu.md:1752`, `.firecrawl/paragraf-zakon-o-radu.md:1760`).
- Zakljucak za kod: javni zakon potvrdjuje zakonske minimume, ali ne potvrdjuje poslodavacke procente iz KU kao sto su 41% i 123%; oni ostaju KU/obracunska-lista pravila.

### Minuli rad

- Ministarstvo rada objasnjava da zakonski minimum 0,4% po godini staza vazi za rad kod aktuelnog poslodavca (`.firecrawl/minrzs-minuli-rad.md:11`, `.firecrawl/minrzs-minuli-rad.md:25`).
- Zakljucak za kod: aplikacijski default 0,5% je uskladjen sa lokalnim KU ako je KU clan 62 tacan za korisnika, ali javni zakon sam potvrdjuje samo minimum 0,4% i pravilo aktuelnog poslodavca/prethodnika.

### Praznici

- Ministarstvo rada navodi neradne drzavne praznike: Nova godina 1-2. januar, Sretenje 15-16. februar, Praznik rada 1-2. maj, Dan primirja 11. novembar (`.firecrawl/minrzs-praznici.md:21` do `.firecrawl/minrzs-praznici.md:28`).
- Isto navodi neradne verske praznike: prvi dan Bozica 7. januar i Vaskrsnji praznici od Velikog petka do drugog dana Vaskrsa (`.firecrawl/minrzs-praznici.md:33`, `.firecrawl/minrzs-praznici.md:34`).
- Ministarstvo rada navodi da se drzavni praznik prenosi na prvi naredni radni dan ako jedan od datuma padne u nedelju, a verski praznici se ne prenose (`.firecrawl/minrzs-praznici.md:15`, `.firecrawl/minrzs-praznici.md:30`).
- Paragraf zakon dodatno navodi Dan pobede kao drzavni praznik, ali Ministarstvo rada ga svrstava u praznike koji se obelezavaju radno (`.firecrawl/minrzs-praznici.md:48` do `.firecrawl/minrzs-praznici.md:53`); ne tretirati ga kao neradni dan bez dodatne potvrde.

## Sta nije potvrdjeno ovim scrape-om

- NCR / vrednost sata 226,0 za 2026. nije potvrdjena javnim izvorima; to je poslodavacka/KU/aneks/obracunska-lista vrednost.
- Regres i topli obrok za 2026/2027 nisu potvrdjeni javnim izvorima; zavise od vazecih akata poslodavca.
- KU procenti 41% za nocni/prekovremeni i 123% za praznik nisu opsti zakonski minimumi; ostaju pravila iz lokalnog kolektivnog ugovora.
- Metodologija osnovice za GO/bolovanje/placeni praznik nije dovoljno precizna iz javnog izvora za implementaciju bez jedne realne obracunske liste.
- Pravilnik poslodavca za cetvorobrigadni visak, slobodne dane i isplatu prekovremenog nije potvrdjen javnim izvorima.

## Veza sa kodom

- `app/src/main/java/com/rad/shifttracker/TaxParameterUpdater.kt`: scrape podrzava trenutne izvore i ocekivane vrednosti za porez, olaksicu i doprinose.
- `app/src/main/java/com/rad/shifttracker/Holidays.kt`: scrape podrzava listu glavnih neradnih drzavnih i verskih praznika i pravilo prenosa drzavnih praznika kada datum padne u nedelju.
- `AUDIT_PLATA_KALKULACIJA.md`: javni izvori podrzavaju postojece zakljucke audita; najveci rizici ostaju stavke koje zahtevaju KU/aneks/obracunsku listu.

## Kako obnoviti lokalni scrape

1. Proveriti auth i kredite: `firecrawl --status`.
2. Ponoviti `firecrawl scrape "<url>" --only-main-content --wait-for 3000 --format markdown -o .firecrawl/<naziv>.md --timing` za URL-ove iz tabele.
3. Pregledati samo relevantne linije preko `rg`, npr. `rg -n "34\\.221|stopi od 10|5,15|0,75|110%|prethodnih 12" .firecrawl`.

