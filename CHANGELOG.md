# Dnevnik izmena

Sve značajne izmene u projektu beleže se ovde, najnovije na vrhu.
Format: verzija (versionName) i datum GGGG-MM-DD.

## [Nije objavljeno] - 2026-10-03
- **Tema:** izbor teme (i „Boje sa pozadine“) uklonjen iz Opcija; aplikacija je uvek tamna. Svetla paleta ostaje u kodu kao rezerva.
- **Biblioteke:** Compose BOM 2025.12.01 (material3 1.4.0), compileSdk 36, `material3-adaptive` 1.2.0.
- **Pokret:** spring umesto tween-a za list i prelaz meseca (`RadMotion`).
- **Široki ekran:** `WindowSizeClass` (Expanded), dodir na dan bira dan i pokazuje panel sa dugmetom „Izmeni“.

## [5.4] - 2026-10-03
Ostatak Faze 5 (dizajn), proveren na Pixel 9 preko adb-a; objavljeno kao 5.4.
- **Kalendar (5.3):** ćelije ≥ 48dp i rastu sa fontom, tekst u ćeliji najmanje 11sp, cela strana skroluje
  (donja traka ostaje); pri fontu > 1.3 statistika i donja traka idu u dva reda.
- **Komponente (5.5):** `OutlinedCard` umesto ručne kartice, `SingleChoiceSegmentedButtonRow` za izbor smene,
  prekovremenog i procenta bolovanja.
- **Svetla tema (5.2):** Opcije → Tema (Tamna / Svetla / Sistem) i „Boje sa pozadine" (Android 12+); tamna ostaje podrazumevana.
  Kontrast ≥ 4.5:1 za svaku boju smene u obe teme je pod testom. Tekst na punoj boji treće smene/bolovanja u tamnoj temi je sada tamni.
- **Pokret (5.8):** sistemsko „ukloni animacije" gasi prelaz meseca i klizanje lista.
- **Adaptivno (5.7):** od 840dp kalendar levo, statistika i plata desno; list ostaje najviše 600dp.

## [5.3] - 2026-10-03
- **Lista:** uključuje se od 15. sledećeg meseca (lista za septembar od 15. oktobra), isti dan kad počinje automatska provera konačne liste iz Gmail-a.

## [5.2] - 2026-10-03
- **Lista:** dugme je isključeno dok lista za mesec ne stigne (20. sledećeg meseca; lista za septembar od 20. oktobra), ne već od 1. u mesecu.

## [5.1] - 2026-10-03
Međuverzija plana „production grade" (faze 3, 5.1, 5.4, 5.6 i 6); izgled skoro isti kao 5.0.
- **Ispravnost podataka:** restore odbija prazan ili nevalidan snapshot, verzija šeme u lokalnom fajlu
  sa migracijama, atomski upis keša i lokalnog fajla.
- **Pristupačnost:** red sa prekidačem/kvadratićem je jedna dodirna površina, greške u formama čita
  čitač ekrana, polja imaju stalnu oznaku i dugme „Dalje/Gotovo" na tastaturi, dugmad najmanje 48dp.
- **Dizajn sistem:** sve boje iz M3 teme (colorScheme + boje smena kroz ExtendedColors), razmaci
  na 4dp mreži (Spacing), težina fonta samo kroz M3 stilove; sitno više vazduha u karticama i listovima.
- **Provera bez telefona:** snimci ekrana na računaru (Roborazzi) za glavni ekran, Opcije i dan.
## [5.0] - 2026-10-03
Pregled dizajna po Material 3 (audit 38/100, sada znatno više): tema, komponente, pristupačnost.
- **Tema:** puna M3 tamna šema (sve uloge, bez podrazumevane ljubičaste), M3 skala oblika
  (`Shapes` 4/8/12/16/28), `THEME` boje izvedene iz iste palete. Tamna pozadina prozora od prvog
  frejma (nema svetlog bljeska pri pokretanju). Compose BOM 2025.05.01.
- **Dugmad i kartice:** `RoundedButton`/`GlowButton` su sada M3 `Button` (ripple, stanja, dodirna
  površina 48dp, oblik „full“); uklonjeni mrtvi parametri (`radius`, `textColor`, `bgColor` kod
  GlowButton-a, `widthRatio`/`accent` kod PopupDialog-a, boje kod PopupCard-a). Pritisak je pravi
  ripple, ne ručni beli overlay.
- **Dijalozi:** list sa dna prati M3 (oblik 28dp, M3 scrim, ručica, ulaz/izlaz emphasized prelazom,
  zatvaranje povlačenjem nadole, nazad-gest). Ostao je na `Dialog` prozoru zbog podešenog ponašanja
  sa tastaturom i isečkom kamere.
- **Poruke:** Snackbar umesto Toast-a (glavni ekran + unutar dijaloga, jer je dijalog poseban prozor).
- **Pristupačnost:** `textFaint` posvetljen (>= 4.5:1), nova `outline` boja (>= 3:1) za ivice polja
  i prekidača, ćelije kalendara imaju pun opis za čitač ekrana (datum, smena, sati, manjak/kompenzacija),
  uloge dugmeta/radio na klikabilnim elementima, strelice za mesec i izbor smene 48dp, donja traka
  sa celim dodirnim stavkama i nazivima >= 9sp.
- **Donja traka:** M3 površina i stavke sa ripple-om; predictive back uključen u manifestu.
- **Layout:** margine 16dp, sadržaj do 600dp širine (tablet/preklopljen telefon), prelaz meseca
  (shared axis).
- **Testovi:** novi `DesignSystemTest` (kontrast boja, pokrivenost teme, opis dana).

## [4.9] - 2026-10-01
- **Dodatna smena:** posle treće redovne može da se izabere prva kao dodatna (prva → druga/treća, druga → treća, treća → prva).

## [4.8] - 2026-09-30
Pregled cele aplikacije: popravke backup-a i čuvanja podataka.
- **Backup u oblak:** greška pri drugom pokušaju posle osvežene prijave više ne ruši posao niti
  ostavlja „Syncing“ (`backupVersioned`, `listVersions`). Rok od 30 s važi samo za čitanje, ne i za
  upis, pa uspešan upis više ne izgleda kao „Timeout“ i ne pravi duplikate verzija.
- **Čuvanje:** gašenje ekrana više ne snima ponovo niti zakazuje cloud backup kad nema izmena
  (ranije je svaki izlazak iz aplikacije slao novu verziju na Drive). Neuspelo snimanje se i dalje
  ponavlja pri izlasku (`RepositoryPersistenceScheduler`).
- **Oštećen/prazan fajl:** lokalni fajl sa samo `{}` ide u karantin umesto da isprazni podatke;
  svaki neispravan lokalni fajl ide u novu kopiju u karantinu (poslednje 3), ne pregazi se.
- **Crash log:** javna kopija u Documents se sada stvarno upisuje (MediaStore Files umesto Downloads).
- Čišćenje (detekt): izdvojena dugmad onboarding dijaloga, uklonjena nekorišćena funkcija, README.
## [1.8] - 2026-09-26
- **Kartice ispod kalendara su preimenovane i obe se vide.** Gornja je „Septembarska plata“
  (rad u mesecu, isplata sledećeg meseca), a donja „Primanja u Septembru“ (šta leže na račun tog
  meseca, sa akontacijom od ~5.). Akontacija se i dalje upisuje pod mesec isplate, pa je vidljiva
  na ekranu meseca u kom je legla (`salaryCardLabel`, `totalCardLabel`).
- Najniža ćelija kalendara je 30dp, da mesec od 6 nedelja stane na 360dp i sa obe kartice.

## [1.7] - 2026-09-26
Provereno na Pixel 9 koji glumi druge telefone (360/393/411 dp, niži ekran, font 1.3 i 1.5).
- **Kalendar staje ceo i na manjim telefonima.** Ćelije se smanjuju da stanu svi redovi
  (i meseci od 6 nedelja); tekst u ćeliji prati visinu, a sistemski font je u ćeliji ograničen
  na 1.15 (`calendarDayHeight`, `calendarCellFontScale`). Ranije je na 360dp poslednji red bio odsečen.
- **Detalji plate:** iznosi se više ne skraćuju u „101566.63 …“; smanjuje se naziv levo.
- **Bolovanje/Godišnji:** polja „Početak/Kraj“ se ne seku pri većem fontu.
- **Donja traka:** nazivi dugmadi staju i pri najvećem fontu.

## [1.6] - 2026-09-26
- **Sakrivena kartica „Primanja u ovom mesecu“** ispod kalendara; ostaje samo „Plata za…“,
  zaobljena i dole. Vraća se prekidačem `SHOW_TOTAL_CARD` u `ShiftDashboardCards.kt`.

## [1.5] - 2026-09-26
- **Iz Opcija sklonjeni „Export rada i obračuna“ i „Debug export“.** Kod ostaje; pale se
  prekidačem `SHOW_TEST_EXPORT_ACTIONS` u `OptionsDialog.kt` kad zatreba za testiranje.

## [Neobjavljeno]

### Izmenjeno (2026-09-25)
- **Prvo pokretanje otvara samo „Popuni smene“.** Uklonjeni su koraci za ažuriranje poreza,
  koeficijent i otežane uslove i minuli rad. Koeficijent, minuli rad, doprinose, olakšicu i
  porez upisuje poslednji učitan listić; bez listića važe podrazumevane vrednosti. Mesečno
  ažuriranje poreza sa interneta (`TaxParameterUpdater`, `MonthlyTaxUpdate`) je obrisano.
- **Čitanje akontacije iz obaveštenja je na novoj instalaciji isključeno** dok ga korisnik ne
  uključi u Opcijama. Postojeći korisnici zadržavaju sačuvanu vrednost (stari backup bez ključa
  i dalje znači uključeno).
- **Uklonjeni ostaci podsetnika za račun hrane i dozvola za tačne alarme:** prozor „Tačni alarmi“
  na početnom ekranu, `FoodReceiptReminder*`, receiver, kanal, ikonica i podešavanje
  `food_receipt_reminder_enabled` (stari JSON sa ključem se i dalje čita). `SCHEDULE_EXACT_ALARM`
  je izbačen iz manifesta; podsetnik za davanje krvi koristi običan alarm
  (`setAndAllowWhileIdle`). Pri pokretanju se kod starih instalacija otkazuje zaostali alarm i
  brišu kanal i prefs (`LegacyCleanup.kt`). Topli obrok (obračun, prikaz, poređenje) nije diran.
- **„Lista“: bruto, neto i za isplatu se ne broje kao neslaganje.** Prikazani su u tabeli bez
  ✓/✗, a broj stavki koje se ne slažu računa se samo iz stvarnih stavki (časovi, minuli rad,
  topli obrok...). Objašnjenje razlike u zbirovima (stimulacije, KIRU, nepoznata razlika) je
  siva napomena „samo informativno“, ne upozorenje.
- **Otežani uslovi rada i prevoz po danu premešteni su iz „Liste“ u Opcije**, u novi odeljak
  „Otežani uslovi i prevoz“ iznad „Odbici od plate“. Iste sačuvane vrednosti, bez migracije.
  Odeljak „Prikaz i podsetnici“ se sada zove „Prikaz i obaveštenja“.

### Dodato (2026-09-24)
- **Listić se sam nalazi u folderu.** Opcije → „Folder sa listićima“ (podrazumevano `Download`,
  ili „Izaberi folder“) i dugme „Pristup fajlovima“ koje otvara „Pristup svim fajlovima“ za
  aplikaciju (kao „Pristup obaveštenjima“). Kad „Lista“ nema listić za mesec, pregleda PDF-ove
  u folderu (do 30, do 1 MB) i mesec i godinu prepoznaje samo iz sadržaja; ime `MM12GG_…pdf`
  (`081262` = avgust 2026) samo pomera verovatne fajlove napred. „Učitaj nađeni listić“ ga čuva
  odmah, sa svim predloženim izmenama.
- **Listić se čuva bez pregleda:** i nađen i ručno izabran PDF („Zameni listić“) upisuju se
  odmah sa svim predloženim izmenama podešavanja; prozor „Uvoz platnih listića“ / „Sačuvaj“ je
  uklonjen. Pogrešan mesec ili nečitljiv PDF se i dalje odbija i ništa se ne menja.
- **„Popuni iz liste“ upisuje odmah** na prvi klik, bez prozora za pregled i „Upiši“.
- „Zameni listić“ i „Obriši listić“ su veća dugmad (rastegnuta celom visinom reda, 56 dp).
- **„Obriši listić“** pored „Zameni listić“ u „Listi“ (uz potvrdu). Brisanje važi i za backup
  (`deleted_payslip_months`), a ponovo učitan listić za taj mesec ga poništava. Upisani dani i
  podešavanja uzeta sa listića ostaju.
- Red „Za isplatu (pre kredita)“ u poređenju sa listićem sada piše samo **„Za isplatu“**.
- **Pravo na godišnji po KU čl. 29** u dijalogu „Godišnji odmor“: staž na 01.01. sa sačuvanih
  listića („Ukupni radni staž“, novo polje parsera; mesec povećanja staža aplikacija sama nalazi
  kad listić pokaže godinu više), noćni rad iz smena/šifre 20, vikend samo četvorobrigada; ručno
  su samo prekidači (povećani rizik, III stepen, socijalni uslovi). Predlog popunjava prazno
  „Ukupno“, a broj sa rešenja ima prednost.
- **Stari listići se brišu:** listić stariji od ~24 meseca pre najnovijeg upisa (isti prozor kao
  za dane) briše se lokalno i iz backup-a u oblaku. Pre brisanja se prenos godišnjeg za prvu celu
  godinu izračuna i zamrzne (`carryover_frozen`), a starije godine godišnjeg se brišu.
- Dani davanja krvi u kalendaru pišu **„Plaćeno odsustvo“** umesto „Krv“ (tako se vode i na
  listiću). Dugme „Krv“ za upis i evidencija davanja (podsetnik, istorija) ostaju.
- **Godišnji odmor se računa iz listića.** Za mesec sa učitanim listićem iskorišćeni dani su
  šifra 10 / 8 h (kalendar tog meseca se ne broji, pa procenjeni datumi iz „Popuni mesec“ ne
  smetaju); za mesec bez listića broji se kalendar. Dijalog „Godišnji odmor“ uvek traži samo
  preneto, pravo za godinu (po rešenju) i potrošeno van aplikacije, računa od 01.01. i
  prikazuje „Iskorišćeno“ po mesecima sa izvorom (npr. „Jul: 12 dana · listić“, a kad se
  kalendar ne slaže „· listić (kalendar 3)“). Prenos u sledeću godinu takođe ide iz listića.
  Stvarni listići 2026: jan 5 + jul 12 + avg 2 = 19 od 24 → ostalo 5.
  Polje „Preneto“ se sakriva posle 30.06. i kad je ranija godina praćena u aplikaciji (tada
  se prenos uvek računa iz nje, i za sačuvanu godinu); „Potrošeno van aplikacije“ se sakriva
  kad svaki završen mesec ima listić ili upisane dane, osim ako je već uneto više od 0.
- **„Lista“ radi po mesecu otvorenom u kalendaru.** Prikazuje listić tog meseca, a
  ispod poređenje sa upisanim danima (režija, GO, plaćeno odsustvo, praznik, bolovanje,
  noćni, nedelja, prekovremeni, izostanci, fond, bruto/neto). Ako za mesec nema listića,
  nudi „Učitaj listić za <mesec> (PDF)“; dugme „Skeniraj novi listić“ je uklonjeno.
  Listić za drugi mesec se odbija sa greškom i ništa se ne učitava. Listići se pamte po
  mesecu u podacima aplikacije i Drive backup-u (`saved_payslips`); stariji listić ne
  predlaže vraćanje koeficijenta, poreza, doprinosa i staža unazad.
- **Tačnija napomena ispod provere obračuna:** navodi tačno stavke koje se ne slažu
  (aplikacija/listić) i razlaže razliku u bruto na delove — stavke bez časova
  (stimulacije, KIRU, regres, Izostanci 1) i novčane redove koji se ne slažu; npr. jul
  2026: „Razlika u bruto (9.131,18) je tačno: Izostanci 1 −330 + Stimulacije 6.823 +
  Topli obrok (TO2) 2.638,18“.
- Provera obračuna poredi i **Topli obrok (TO2)** i **iznos minulog rada (MR)**; parser
  čita i obustavu „Topli obrok“. Ako je pojedeno više nego što pripada, „Lista“ to javlja
  sa iznosom (npr. 02/2026: 210,00 više); inače ništa.
- **„Zameni listić (PDF)“** u „Listi“ za ispravljen obračun (ista provera meseca).
- **„Popuni iz liste“** u „Listi“: kad mesec ima učitan listić, a nema korisnikovih upisa,
  ispod listića je dugme (ne iskačući prozor) koje raspoređuje dane po tom listiću, sa
  pregledom pre upisa. Kao „neupisano“ se ne broje smene i praznici iz „Popuni“ ni prazan
  slobodan dan vikendom/praznikom; dugme se ne nudi ni kad se časovi već slažu. Opcija
  „Popuni mesec po platnom listiću (PDF)“ je uklonjena iz Opcija.

### Uklonjeno (2026-09-24)
- **Unos „Potrošeni topli obrok — neto“** u detaljima plate. Obračun uvek računa kao da
  je pojedeno koliko pripada; ranije uneti iznosi ostaju u backup-u, ali se ignorišu.
- **Podsetnik „uzmi račun za hranu“** (opcija i obaveštenje); stari zakazani alarm se
  gasi pri pokretanju.
- Kalendar: umesto „GO“ piše „Godišnji / odmor“, a umesto „PO“ „Plaćeno / odsustvo“
  (u dva reda).
- **Popuni mesec po platnom listiću** (Opcije). Iz PDF listića se prazan mesec popuni
  po upisanom šablonu smena (isti kao za neupisane mesece; bez šablona se odbija).
  GO, plaćeno odsustvo, bolovanje i izostanci se raspoređuju kao uzastopni dani, a
  skraćeni dani, rad vikendom i produžene smene tako da se časovi (režija, GO, plaćeno
  odsustvo, praznik, bolovanje, noćni, nedelja, prekovremeni, izostanci, fond) tačno
  slažu sa listićem. Raspored se pre upisa proverava punim obračunom na probnoj kopiji i
  prikazuje dan po dan; upis tek na „Upiši“.
- **Tip dana „Plaćeno odsustvo“** u izmeni dana (100% po osnovici, šifra 11; u
  kalendaru „PO“). Odvojen je od davanja krvi i ne dira podsetnike.

### Promenjeno (2026-09-24)
- **„Satnica“ je preimenovana u „Lista“.** Uklonjena je lista osnovica sa „Obriši“
  (osnovica se ažurira skeniranjem novog listića), kao i ručni unos „Izostanci 1“
  u pregledu plate.
- **„Satnica“ je sada čitač platnog listića.** Ručni unos koeficijenta, vrednosti
  sata, doprinosa, olakšice, poreza i poznate osnovice je uklonjen. Dugme „Skeniraj
  novi listić (PDF)“ (jedan ili više fajlova) čita sa listića: koeficijent, vrednost
  sata (po mesecu), doprinose radnika, olakšicu, porez, minuli rad, osnovicu naknada
  (GO 10 → plaćeno odsustvo 11 → praznik 12 → bolovanje 40 sa procentom sa listića).
  Akontacija se sa listića namerno ne čita. Izmene se prikazuju „sada → listić“ i upisuju tek na „Sačuvaj“. Koeficijent, porez,
  doprinosi i staž uzimaju se samo sa najnovijeg listića. Podaci poslednjeg listića
  ostaju prikazani u Satnici, uz proveru obračuna (časovi po šifri, bruto, neto).
  Ručno su ostali samo otežani uslovi rada i prevoz po danu. Osnovica je uvek
  „Poznata“ (sa listića).
- **Uklonjeno internet ažuriranje poreza** (dugme i automatsko mesečno pri pokretanju),
  jer bi pregazilo vrednosti sa listića. Ostalo je samo jednokratno u uvodnom podešavanju.
- Tekst listića se obrađuje samo u memoriji; čuvaju se samo izvučeni brojevi, bez
  imena, JMBG-a i računa. Nova zavisnost: PdfBox-Android. `liste/` i `*.pdf` su u `.gitignore`.

### Promenjeno (2026-09-22)
- **Odrada je automatska, bez dugmeta.** Uz dodatnu smenu opet postoje samo
  „Plaćeno" i „Za sate". Višak preko 8h radnim danom („Plaćeno") prvo odrađuje
  CELE radne dane koje u tom mesecu nisi radio (upisan „Nisam radio", bez praznika
  i bez „iskoristi sate") i plaća se kao redovan rad sa noćnim dodatkom;
  prekovremeni je samo ostatak. Dan duži od 12h nikad nije odrada. Kraći dani ne
  upijaju višak. Ovo pravilo daje tačno listiće 06/2026 (6h prekovremenog) i
  07/2026 (17h). Stare oznake „Odrada" čitaju se kao „Plaćeno".
- **Praznik i treća smena po satu.** Dodatak 123% ide samo na sate koji padaju na
  praznik: treća smena koja počinje na praznik ima 2h prazničnog rada (22–24h),
  a treća smena uoči praznika 6h (00–06h).

### Ispravljeno (provera obračuna sa listićima 05–08/2026, 2026-09-22)
- **Odrađeni manjak se više ne plaća dvaput.** Kad je višak pokrio neradni dan,
  isti manjak se ranije plaćao još jednom iz pozitivnog salda sati (npr. 2h ≈
  1.319 din bruto viška), a saldo je padao za te sate. Isto važi za četvorobrigadu
  kad višak u fondu pokrije manjak. Saldo sada pamti odrađene sate i u narednom mesecu.
- **Davanje krvi ostaje 100%** kad se na taj dan uključi ili isključi praznik
  (ranije je dan postajao obično bolovanje od 65%).
- **Noćni dodatak samo za pune sate treće smene.** Listić 07/2026: 31.07. rađeno
  4h30 u trećoj smeni, noćni rad 4h (program je plaćao 4,5h, +130 din bruto).
  Jul 2026 sada daje 60h noćnog kao listić.
- **Topli obrok u proseku za osnovicu** računa se istim pravilom kao na plati
  (prag 6h, cene po periodima, treća smena sa kraja prethodnog meseca). Ranije je
  prosek koristio stari prag od 4h i jednu cenu, pa je osnovicu malo precenjivao.
- **Dodatak 123% samo za stvarni rad na praznik.** Dan označen kao praznik, ali
  „nije rad na praznik", više ne dobija praznični dodatak.

### Ispravljeno (Restore na novoj instalaciji vraćao pola podataka i pomerene smene)
- **Restore više ne vraća sopstvenu sigurnosnu kopiju.** „Vrati iz oblaka" je prvo
  pravio sigurnosnu kopiju trenutnog stanja, pa vraćao „najnoviju verziju" — a to je
  tada bila upravo ta kopija: sveže stanje nove instalacije (prazna podešavanja +
  nova auto-popuna smena) spojeno sa arhivom. Otud pola podataka i trobrigada
  pomerena za sedmicu. Sada se cilj skida PRE kopije, a sigurnosne kopije se
  obeležavaju (`radSafety`) i nikad nisu „najnovija verzija" ni arhiva za spajanje;
  u listi verzija su jasno označene i imaju svoju retenciju (poslednjih 5).
- **Nova instalacija ne šalje backup preko postojećeg.** Dok uređaj ne preuzme
  backup iz oblaka (Restore) ili korisnik izričito ne izabere „Zadrži ovaj uređaj",
  automatski backup je pauziran, a ručni prvo pita. Odmah posle prijave na Google
  nudi se „Vrati sve iz oblaka". Postojeće instalacije (koje su već slale backup)
  nastavljaju bez pitanja.
- Test `backup pa restore na novoj instalaciji vraca identicno stanje` poredi ceo
  JSON i svaku smenu dan po dan.

### Ispravljeno (gubitak ručno unetih podataka u Google Drive backup-u)
- **Satnice se više ne orezuju.** Retencija (~24 meseca) je pri SVAKOM lokalnom
  čuvanju brisala satnice starije od prozora i sabijala ih u jednu na granici — pre
  nego što stignu u oblak. Sada se sve unete satnice čuvaju i lokalno i u oblaku.
- **Prazno stanje više ne gazi arhivu u oblaku.** Backup sa nove instalacije (pre
  restore-a) je u najnoviju verziju upisivao prazna davanja krvi i datum početka
  rada 0. `CloudArchiveMerger` sada: satnice spaja kao uniju arhive i trenutnog
  stanja (obrisane pamti `deleted_hourly_value_months`), a davanja krvi i početak
  rada/minuli rad uzima iz arhive kad ih trenutno stanje nema.
- Auto-backup se preskače dok prvo pokretanje (onboarding) nije završeno.

### Ispravljeno (poređenje sa platnim listićima 01–08/2026)
- **Osnovica bolovanja više nije zakucana na 918,81.** Po KU čl. 66 bolovanje se
  plaća 65% (ili 100%) iste osnovice kao GO i praznik (čl. 65 st. 3), pa sada
  prati izabranu osnovicu naknada. 918,81 sa listića 03/2026 je bio samo martovski
  prosek; sledeće bolovanje bi inače bilo potcenjeno (~920 din bruto na 40 h).
- **Prekovremeni označen kao „Plaćeno" se više ne netira sa manjkom sati.** Listić
  07/2026 je platio +3h od 01.07. kao prekovremeno iako je 31.07. ostao manjak
  (17h prekovremenog + 3h izostanaka); program je ranije davao 14h.

### Dodato
- **Osnovica sa svakog platnog listića.** U *Podešavanja plate → Poznata osnovica*
  sada se unosi lista osnovica po mesecima (Dodaj/promeni, Obriši) umesto jedne.
  Svaki mesec koristi najnoviju osnovicu koja nije posle njega, pa se greška
  prenosa ne gomila. Čuva se u JSON-u kao `known_pay_base_anchors`; stari ključevi
  `known_pay_base_month`/`known_pay_base_gross_hourly` i dalje nose najnovije sidro,
  a stari bekapi sa jednim sidrom se normalno učitavaju. Mesec sidra više ne mora
  biti pre prikazanog meseca.
- ~~**Način vođenja sati preko 8h: „Odrada".**~~ (uklonjeno 2026-09-22, sada automatski) Pored „Plaćeno" (uvek prekovremeni
  +41%) i „Za sate" (saldo slobodnih sati), „Odrada" plaća sate sa svim dodacima
  (noćni, nedelja), ali oni prvo pokrivaju manjak iz istog meseca kao redovan rad,
  a prekovremeno je samo višak — kao na listiću 06/2026 (+2h 03.06. i +3h 25.06.).
  Nudi se samo za radni dan. **Napomena:** dane koji su se do sada automatski
  netirali treba ručno označiti kao „Odrada" (npr. 03.06. i 25.06.2026).
- **Upozorenja „Proveri podešavanja"** u detaljima plate i u *Sati rada*: kad staž
  za minuli rad nije unet (minuli rad = 0) i kad osnovica za GO/praznik/odsustvo
  dolazi iz simuliranih meseci ili podrazumevane vrednosti. Isto upozorenje stoji
  i u *Podešavanjima plate* uz varijantu „Realnih + simuliranih". Na stvarnim
  podacima 05–08/2026 ta dva podešavanja su nosila 2.100–9.900 din neto mesečno.
- **Objašnjenje stavke „Izostanci 1"**: na listiću je to zaokruživanje minuta
  (režija u celim satima, ostatak minuta × satnica × (1 + minuli rad)), pa se ne
  unosi ako su minuti već upisani po danima. *Sati rada* prikazuju procenu te
  stavke uz „Izostanci po fondu" (npr. 3h 30min → „≈ -330 RSD").

### Ispravljeno
- Bruto toplog obroka (neto / 0,701) zaokružuje se na dve decimale kao na
  platnom listiću (05/2026: 7.817,40; 07/2026: 4.129,81), umesto na ceo dinar.
- Dnevni pregled kategorija (noćni i nedeljni rad) više ne seče dodatne
  minute dana na cele sate; usklađen je sa mesečnim obračunom koji ih plaća.

### Dodato
- **Prekidač „Prevoz u iznosu za isplatu"** u *Opcijama* (sekcija „Prikaz ispod
  kalendara"). Podrazumevano uključen, kao i do sada: kartice „Plata za…" i
  „Primanja u ovom mesecu" prikazuju neto platu (i regres) sa prevozom. Kad se
  isključi, prikazuju samo neto platu (i regres) bez prevoza; detalji plate i
  dalje prikazuju prevoz kao zasebnu stavku. Čuva se u JSON-u
  (`main_pay_includes_transport`, stari bekapi ostaju sa prevozom).
- **Izostanci po fondu (listić)** u dijalogu *Sati rada*: fond meseca minus svi
  sati plaćeni po osnovi bez prekovremenih, isto što platni listić prikazuje
  pod šifrom 95. Dosadašnji red „Izostanci" (dnevni manjak koji hrani saldo
  sati) preimenovan je u „Manjak po danima (saldo)". Isti broj ide i u export
  (`fundAbsenceMinutes`).
- **Predviđanje akontacije po KU (član 91).** U dijalogu *Akontacija* prikazuje se
  minimalna akontacija po kolektivnom ugovoru Valjaonice Bakra — 40% zarade
  isplaćene u prethodnom kalendarskom mesecu (akontacija + konačna isplata +
  regres; prevoz ne ulazi) — uz dugme „Unesi minimum" za brzo popunjavanje.
  Isti minimum se prikazuje i kao napomena ispod unosa akontacije u detaljima
  plate. Pravilo je verifikovano na platnim listićima 01–07/2026: nijedan mesec
  ne krši minimum, a jul 2026 je isplaćen tačno na minimumu.

## [1.3] - 2026-07-10

### Ispravljeno
- Poreski parametri iz keša koriste se samo u istom kalendarskom mesecu i
  aplikacija jasno označava kada mrežno ažuriranje nije uspelo.
- Poslednje lokalno snimanje se prazni pri izlasku aplikacije, a neuspešan upis
  se prijavljuje korisniku pri sledećem ulasku.
- Dozvola za tačne alarme traži se samo kada je bar jedan podsetnik aktivan.
- Automatski uvoz akontacije prihvata samo poznate NLB/mKlik pakete, bez
  preširokog poklapanja svakog naziva koji sadrži `nlb`.
- Lokalni release ostaje potpisan postojećim debug ključem radi direktne
  nadogradnje telefona, dok lokalni `keystore.properties` automatski prebacuje
  build na pravi release ključ kada se on konfiguriše.

### Dodato
- **Automatski backup u pozadini.** Posle svake izmene podataka zakazuje se
  (debounce ~10 min, da se niz brzih izmena spoji u jedan) pozadinski backup
  preko `WorkManager`-a, uz dnevni „safety-net" ako se dugo ništa ne menja. Radi
  samo kad ima mreže i kad je nalog povezan; pri neuspehu se ponavlja uz backoff.
- **Vraćanje konkretne ranije verzije.** U *Cloud Backup* dodato „Vrati raniju
  verziju…" — lista svih sačuvanih verzija sa vremenom, sadržajem (meseci/dani/
  sati) i oznakom mogućeg pada podataka. Pre vraćanja se traži potvrda i napravi
  sigurnosna kopija trenutnog stanja, pa je i sam restore poništiv.

### Promenjeno
- **Verzionisani Google backup.** Svaki backup se sada upisuje kao zaseban fajl
  `rad_backup_<vreme>.json` umesto da prepisuje jedan jedini fajl, pa se čuva
  više starijih verzija i može se vratiti ranije stanje. Zadržavanje je
  deda-otac-sin (poslednjih nekoliko + dnevno/nedeljno/mesečno), uz garanciju da
  se poslednja ispravna verzija nikad ne obriše.
- **Zaštita od slučajnog brisanja.** Pre upisa se novi backup poredi sa
  poslednjom dobrom verzijom; ako liči na masovno brisanje (nestao ceo mesec,
  obrisani svi podaci, pad dana ili radnih sati preko 50%), verzija se i dalje
  sačuva ali se označi i korisnik dobije upozorenje — ranije verzije ostaju
  netaknute. Spajanje sa prethodnom arhivom (puna istorija) i prekid upisa kad
  se arhiva ne može pročitati su zadržani.

## [1.2] - 2026-06-25

### Promenjeno
- **Backup u oblak čuva punu istoriju.** Lokalni podaci se i dalje automatski
  orezuju na ~24 meseca radi brzine, ali Google Drive backup sada akumulira
  kompletnu istoriju: pri svakom backup-u se prethodna arhiva iz oblaka spaja sa
  trenutnim stanjem (`exportCloudBackupJson` + `CloudArchiveMerger`), pa stari
  meseci van prozora ostaju sačuvani. Stariji od prozora dolaze iz arhive,
  meseci u prozoru iz trenutnog stanja (poštuju se izmene i brisanja). Početni
  saldo sati/odmora se za arhivu uzima originalan (iz arhive) da se ne broji
  duplo. Ako se postojeća arhiva ne može pročitati, backup se prekida bez upisa
  da se puna arhiva ne pregazi orezanim podacima.

## [1.1] - 2026-06-24

### Dodato
- **Sindikalna članarina** (NEZ.SIND.RAD.JUGOSLAVIJE) — opciona obustava iz neto
  zarade. U *Opcije* dodato podešavanje (član sindikata + stopa, podrazumevano
  1,5%). Članarina = `round2(netPayBeforeDeductions × stopa)` (HALF_UP), umanjuje
  prikazanu platu (`displayPay`), a NE dira poresku osnovicu, doprinose ni porez.
  Prikazana kao zasebna stavka u razradi plate i u audit exportu
  (`netDeductionsBreakdown.unionDuesNet`). Podešavanje se čuva u JSON-u.

## [1.0] - 2026-06-24

### Dodato
- Projekat povezan sa GitHub repozitorijumom (`Sogli/rad`), uz `.gitignore`,
  `README.md` i ovaj dnevnik izmena.

### Uklonjeno
- Opcija **„Popuni mesec po platnom listiću"** iz menija *Opcije* i ceo prateći
  feature za rekonstrukciju meseca po platnom listiću:
  - dijalog, servis i modeli (`PayrollSlip*`),
  - metode `applyPayrollSlipReconstruction` i
    `calculatePayrollSlipPreviewNetBeforeDeductions` u repozitorijumu,
  - svi prateći unit testovi.
