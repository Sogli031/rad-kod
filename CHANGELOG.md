# Dnevnik izmena

Sve značajne izmene u projektu beleže se ovde, najnovije na vrhu.
Datumi su u formatu GGGG-MM-DD.

## 2026-06-24

### Dodato
- Projekat povezan sa GitHub repozitorijumom (`Sogli/rad`), uz `.gitignore`,
  `README.md` i ovaj dnevnik izmena.
- **Sindikalna članarina** (NEZ.SIND.RAD.JUGOSLAVIJE) — opciona obustava iz neto
  zarade. U *Opcije* dodato podešavanje (član sindikata + stopa, podrazumevano
  1,5%). Članarina = `round2(netPayBeforeDeductions × stopa)` (HALF_UP), umanjuje
  prikazanu platu (`displayPay`), a NE dira poresku osnovicu, doprinose ni porez.
  Prikazana kao zasebna stavka u razradi plate i u audit exportu
  (`netDeductionsBreakdown.unionDuesNet`). Podešavanje se čuva u JSON-u.

### Uklonjeno
- Opcija **„Popuni mesec po platnom listiću"** iz menija *Opcije* i ceo prateći
  feature za rekonstrukciju meseca po platnom listiću:
  - dijalog, servis i modeli (`PayrollSlip*`),
  - metode `applyPayrollSlipReconstruction` i
    `calculatePayrollSlipPreviewNetBeforeDeductions` u repozitorijumu,
  - svi prateći unit testovi.

  Verifikacija: `testDebugUnitTest` i `detekt` prolaze.
