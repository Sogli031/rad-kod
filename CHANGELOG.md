# Dnevnik izmena

Sve značajne izmene u projektu beleže se ovde, najnovije na vrhu.
Datumi su u formatu GGGG-MM-DD.

## 2026-06-24

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

  Verifikacija: `testDebugUnitTest` i `detekt` prolaze.
