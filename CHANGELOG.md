# Dnevnik izmena

Sve značajne izmene u projektu beleže se ovde, najnovije na vrhu.
Format: verzija (versionName) i datum GGGG-MM-DD.

## [Neobjavljeno]

### Ispravljeno
- Dnevni pregled kategorija (noćni i nedeljni rad) više ne seče dodatne
  minute dana na cele sate; usklađen je sa mesečnim obračunom koji ih plaća.

### Dodato
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
