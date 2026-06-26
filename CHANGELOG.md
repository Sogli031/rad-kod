# Dnevnik izmena

Sve značajne izmene u projektu beleže se ovde, najnovije na vrhu.
Format: verzija (versionName) i datum GGGG-MM-DD.

## [Neobjavljeno]

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
